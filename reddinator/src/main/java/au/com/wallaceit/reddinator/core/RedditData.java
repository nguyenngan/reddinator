/*
 * Copyright 2013 Michael Boyde Wallace (http://wallaceit.com.au)
 * This file is part of Reddinator.
 *
 * Reddinator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Reddinator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Reddinator (COPYING). If not, see <http://www.gnu.org/licenses/>.
 */
package au.com.wallaceit.reddinator.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Base64;

import okhttp3.FormBody;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import au.com.wallaceit.reddinator.activity.OAuthView;

public class RedditData {
    private SharedPreferences sharedPrefs;
    private OkHttpClient httpClient;
    private static final String OAUTH_ENDPOINT = "https://oauth.reddit.com";
    public static final String OAUTH_CLIENTID = "wY63YAHgSPSh5w";
    public static final String OAUTH_SCOPES = "mysubreddits,vote,read,submit,edit,identity,subscribe,save,history,privatemessages,report";
    public static final String OAUTH_REDIRECT = "oauth://reddinator.wallaceit.com.au";
    private String userAgent;
    private JSONObject oauthAppToken = null;
    private JSONObject oauthToken = null;
    private String oauthstate = null; // random string for secure oauth flow
    private JSONObject userInfo;
    private String username;
    private int inboxCount = 0;
    private long lastUpdateTime = 0;

    public RedditData(Context context) {
        // set user agent
        userAgent = "android:au.com.wallaceit.reddinator:v";
        try {
            PackageInfo manager = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            userAgent += manager.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        userAgent += " (by /u/micwallace)";
        // load account
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        String tokenStr = sharedPrefs.getString("oauthtoken", null);
        try {
            userInfo = new JSONObject(sharedPrefs.getString("user_info", "{}"));
        } catch (JSONException e) {
            e.printStackTrace();
            userInfo = new JSONObject();
        }
        username = sharedPrefs.getString("username", "");
        inboxCount = sharedPrefs.getInt("inbox_count", 0);
        lastUpdateTime = sharedPrefs.getLong("last_info_update", 0);
        if (tokenStr!=null) {
            try {
                oauthToken = new JSONObject(tokenStr);
            } catch (JSONException e) {
                e.printStackTrace();
                oauthToken = null;
            }
        }
        // load app only oauth token
        String appTokenStr = sharedPrefs.getString("oauthAppToken", null);
        if (appTokenStr!=null) {
            try {
                oauthAppToken = new JSONObject(appTokenStr);
            } catch (JSONException e) {
                e.printStackTrace();
                oauthAppToken = null;
            }
        }
    }

    private Intent getLoginIntent(Context context, boolean newTask){
        Intent loginintent = new Intent(context, OAuthView.class);
        oauthstate = UUID.randomUUID().toString();
        loginintent.putExtra("oauthstate", oauthstate);
        if (newTask) // widget requires new task as its intent is not an activity intent (causes runtime exception)
            loginintent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return loginintent;
    }

    // ACCOUNT CONTROL
    public void initiateLogin(Context context, boolean newTask) {
        Intent loginintent = getLoginIntent(context, newTask);
        context.startActivity(loginintent);
    }

    public void initiateLoginForResult(Activity context, boolean newTask) {
        Intent loginintent = getLoginIntent(context, newTask);
        context.startActivityForResult(loginintent, 2);
    }

    public void purgeAccountData() {
        oauthToken = null;
        username = null;
        userInfo = new JSONObject();
        SharedPreferences.Editor edit = sharedPrefs.edit();
        edit.remove("oauthtoken");
        edit.remove("user_info");
        edit.remove("username");
        edit.remove("inbox_count");
        edit.remove("last_info_update");
        edit.apply();
    }

    // NON-AUTHED REQUESTS
    public JSONArray getPopularSubreddits() throws RedditApiException {
        JSONArray subreddits;
        String url = OAUTH_ENDPOINT + "/subreddits/popular.json?limit=50";
        try {
            subreddits = redditApiGet(url, false).getJSONObject("data").getJSONArray("children");
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
        return subreddits;
    }

    JSONArray getDefaultSubreddits() throws RedditApiException {
        JSONArray subreddits;
        String url = OAUTH_ENDPOINT + "/subreddits/default.json";
        try {
            subreddits = redditApiGet(url, false).getJSONObject("data").getJSONArray("children");
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
        return subreddits;
    }

    public JSONArray searchSubreddits(String query) throws RedditApiException {
        JSONArray subreddits;
        String url = OAUTH_ENDPOINT + "/subreddits/search.json?q=" + Uri.encode(query);
        try {
            subreddits = redditApiGet(url, true).getJSONObject("data").getJSONArray("children");
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
        return subreddits;
    }

    public JSONArray searchRedditNames(String query) throws RedditApiException {
        JSONArray names;
        String url = OAUTH_ENDPOINT + "/api/search_reddit_names.json?include_over_18=true&query=" + Uri.encode(query);
        try {
            names = redditApiPost(url).getJSONArray("names");
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
        return names;
    }

    public JSONObject getSubmitText(String subreddit) throws RedditApiException {

        String url = OAUTH_ENDPOINT + "/r/"+subreddit+"/api/submit_text.json";
        return redditApiGet(url, false);
    }

    public JSONObject getSubredditInfo(String subreddit) throws RedditApiException {

        String url = OAUTH_ENDPOINT + "/r/"+subreddit+"/about.json";
        try {
            return redditApiGet(url, false).getJSONObject("data");
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
    }

    public JSONArray getRedditFeed(String feedPath, String sort, int limit, String afterid) throws RedditApiException {
        // allows a logged in user to retrieve the default front page
        boolean authedFeed = true;
        if (feedPath.equals("/default")){
            authedFeed = false;
            feedPath = "";
        }

        String url = OAUTH_ENDPOINT + feedPath + "/" + sort + ".json?limit=" + String.valueOf(limit) + (!afterid.equals("0") ? "&after=" + afterid : "");
        JSONObject result;
        JSONArray feed;

        result = redditApiGet(url, authedFeed); // use oauth if logged in and not requesting default front page
        try {
            feed = result.getJSONObject("data").getJSONArray("children");
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
        return feed;
    }

    public JSONObject getRandomSubreddit() throws RedditApiException {
        String url = OAUTH_ENDPOINT + "/r/random/about.json";
        JSONObject result;

        result = redditApiGet(url, true);
        try {
            result = result.getJSONObject("data");
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
        return result;
    }

    public JSONArray searchRedditPosts(String query, String feedPath, boolean restrictSub, String sort, String time, int limit, String afterid) throws RedditApiException {

        String url;
        try {
            url = OAUTH_ENDPOINT + feedPath + "/search.json?q=" + URLEncoder.encode(query, "UTF-8") + "&t=" + time + "&sort=" + sort + "&restrict_sr=" + restrictSub + "&type=link&syntax=plain&limit=" + String.valueOf(limit) + (!afterid.equals("0") ? "&after=" + afterid : "");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new RedditApiException("Encoding error: "+e.getMessage());
        }
        JSONObject result;
        JSONArray feed;

        result = redditApiGet(url, true); // use oauth if logged in
        try {
            feed = result.getJSONObject("data").getJSONArray("children");
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
        return feed;
    }

    public JSONArray getCommentsFeed(String permalink, String sort, int limit) throws RedditApiException {
        boolean loggedIn = isLoggedIn();
        String url = OAUTH_ENDPOINT + permalink + ".json?api_type=json&sort=" + sort + "&limit=" + String.valueOf(limit);

        return redditApiGetArray(url, loggedIn);
    }

    public JSONArray getCommentsContextFeed(String permalink, String commentId, String sort, int context) throws RedditApiException {
        boolean loggedIn = isLoggedIn();
        String url = OAUTH_ENDPOINT + permalink + commentId + ".json?api_type=json&sort=" + sort + "&context=" + String.valueOf(context);

        return redditApiGetArray(url, loggedIn);
    }

    public JSONArray getChildComments(String moreId, String articleId, String children, String sort) throws RedditApiException {

        String url = OAUTH_ENDPOINT + "/api/morechildren?api_type=json&sort=" + sort + "&id=" + moreId + "&link_id=" + articleId + "&children=" + children;

        JSONArray feed = new JSONArray();

        try {
            JSONObject result = redditApiPost(url);
            if (result != null) {
                feed = result.getJSONObject("json").getJSONObject("data").getJSONArray("things");
            }
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
        return feed;
    }

    // AUTHED CALLS
    public String getUsername(){
        return username;
    }

    public int getInboxCount(){
        return inboxCount;
    }

    public long getLinkKarma(){
        try {
            return userInfo.getLong("link_karma");
        } catch (JSONException e) {
            e.printStackTrace();
            return 0;
        }
    }

    public long getCommentKarma(){
        try {
            return userInfo.getLong("comment_karma");
        } catch (JSONException e) {
            e.printStackTrace();
            return 0;
        }
    }

    /*public JSONObject getCachedUserInfo(){
        return userInfo;
    }*/

    public long getLastUserUpdateTime(){ return lastUpdateTime; }

    public void clearStoredInboxCount(){
        inboxCount = 0;
        SharedPreferences.Editor edit = sharedPrefs.edit();
        edit.putInt("inbox_count", inboxCount);
        edit.apply();
    }

    // updates internally tracked user info and saves it to preference. This is also used for saving oauth token for the first time.
    public void updateUserInfo() throws RedditApiException {
        userInfo = getUserInfo();
        try {
            username = userInfo.getString("name");
            inboxCount = userInfo.getInt("inbox_count");
            lastUpdateTime = System.currentTimeMillis();
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }
        saveUserData();
    }

    private JSONObject getUserInfo() throws RedditApiException {
        checkLogin();

        JSONObject resultjson;
        String url = OAUTH_ENDPOINT + "/api/v1/me";
        try {
            resultjson = redditApiGet(url, true);

            if (resultjson.has("errors") && resultjson.getJSONArray("errors").length()>0) {
                throw new RedditApiException("API error: "+resultjson.getJSONArray("errors").getJSONArray(0).getString(1));
            }
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }

        return resultjson;
    }

    public JSONObject getKarmaBreakdown() throws RedditApiException {
        checkLogin();

        JSONObject resultjson;
        String url = OAUTH_ENDPOINT + "/api/v1/me/karma";
        try {
            resultjson = redditApiGet(url, true);

            if (resultjson.has("errors") && resultjson.getJSONArray("errors").length()>0) {
                throw new RedditApiException("API error: "+resultjson.getJSONArray("errors").getJSONArray(0).getString(1));
            }
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }

        return resultjson;
    }

    public JSONObject getTrophies() throws RedditApiException {
        checkLogin();

        JSONObject resultjson;
        String url = OAUTH_ENDPOINT + "/api/v1/me/trophies";
        try {
            resultjson = redditApiGet(url, true);

            if (resultjson.has("errors") && resultjson.getJSONArray("errors").length()>0) {
                throw new RedditApiException("API error: "+resultjson.getJSONArray("errors").getJSONArray(0).getString(1));
            }
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }

        return resultjson;
    }

    public boolean vote(String id, int direction) throws RedditApiException {
        checkLogin();

        JSONObject resultjson;
        String url = OAUTH_ENDPOINT + "/api/vote?id=" + id + "&dir=" + String.valueOf(direction) + "&api_type=json";
        try {
            resultjson = redditApiPost(url);

            if (resultjson.has("errors") && resultjson.getJSONArray("errors").length()>0) {
                JSONArray errors = resultjson.getJSONArray("errors");
                JSONArray firsterror = (JSONArray) errors.get(0);
                if (firsterror.get(0).equals("USER_REQUIRED")) {
                    oauthToken = null; // bearer token invalid, nullify
                    throw new RedditApiException("Authentication Error, Reddit Login Required", true); // creds invalid re-authenticate.
                }
                return false;
            } else {
                return true;
            }
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
    }

    public JSONArray getAccountFeed(String type, String sort, int limit, String afterid) throws RedditApiException {
        checkLogin();

        String url = OAUTH_ENDPOINT + "/user/" + username + "/" + type + "/.json?sort=" + sort + "&limit=" + String.valueOf(limit) + (afterid!=null ? "&after=" + afterid : "");
        JSONObject result;
        JSONArray feed;

        result = redditApiGet(url, true); // use oauth if logged in
        try {
            feed = result.getJSONObject("data").getJSONArray("children");
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
        return feed;
    }

    public JSONArray getMessageFeed(String type, int limit, String afterid) throws RedditApiException {
        checkLogin();

        String url = OAUTH_ENDPOINT + "/message/" + type + ".json?limit=" + String.valueOf(limit) + (afterid!=null ? "&after=" + afterid : "");
        JSONObject result;
        JSONArray feed;

        result = redditApiGet(url, true); // use oauth if logged in
        try {
            feed = result.getJSONObject("data").getJSONArray("children");
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
        return feed;
    }

    public void markMessagesRead(ArrayList<String> redditIds) throws RedditApiException {
        checkLogin();
        redditApiPost(OAUTH_ENDPOINT + "/api/read_message?id="+ TextUtils.join(",", redditIds));
    }

    /*public void markAllMessagesRead() throws RedditApiException {
        checkLogin();
        redditApiPost(OAUTH_ENDPOINT + "/api/read_all_messages");
    }*/

    public void composeMessage(String to, String subject, String text, String fromSubreddit) throws RedditApiException {
        checkLogin();
        String url;
        try {
            url = OAUTH_ENDPOINT + "/api/compose?api_type=json&to=" + to + "&subject=" + URLEncoder.encode(subject, "UTF-8") + "&text=" + URLEncoder.encode(text, "UTF-8") + (fromSubreddit!=null ? "&from_sr=" + fromSubreddit : "");
        } catch (UnsupportedEncodingException e) {
            throw new RedditApiException("Encoding error: "+e.getMessage());
        }
        redditApiPost(url);
    }

    public JSONObject postComment(String parentId, String text) throws RedditApiException {

        checkLogin();

        JSONObject resultjson;

        try {
            String url = OAUTH_ENDPOINT + "/api/comment?thing_id=" + parentId + "&text=" + URLEncoder.encode(text, "UTF-8") + "&api_type=json";

            resultjson = redditApiPost(url).getJSONObject("json");

            if (resultjson.has("errors") && resultjson.getJSONArray("errors").length()>0) {
                JSONArray errors = resultjson.getJSONArray("errors");
                JSONArray firsterror = (JSONArray) errors.get(0);
                if (firsterror.get(0).equals("USER_REQUIRED")) {
                    oauthToken = null; // bearer token invalid, nullify
                    throw new RedditApiException("Authentication Error, Reddit Login Required", true); // creds invalid re-authenticate.
                }
                throw new RedditApiException("API Error: "+firsterror.get(1), false);
            } else {
                return resultjson.getJSONObject("data").getJSONArray("things").getJSONObject(0).getJSONObject("data");
            }
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new RedditApiException("Encoding error: "+e.getMessage());
        }
    }

    public JSONObject editComment(String thingId, String text) throws RedditApiException {

        checkLogin();

        JSONObject resultjson;

        try {
            String url = OAUTH_ENDPOINT + "/api/editusertext?thing_id=" + thingId + "&text=" + URLEncoder.encode(text, "UTF-8") + "&api_type=json";

            resultjson = redditApiPost(url).getJSONObject("json");

            if (resultjson.has("errors") && resultjson.getJSONArray("errors").length()>0) {
                JSONArray errors = resultjson.getJSONArray("errors");
                JSONArray firsterror = (JSONArray) errors.get(0);
                if (firsterror.get(0).equals("USER_REQUIRED")) {
                    oauthToken = null; // bearer token invalid, nullify
                    throw new RedditApiException("Authentication Error, Reddit Login Required", true); // creds invalid re-authenticate.
                }
                throw new RedditApiException("API Error: "+firsterror.get(1), true);
            } else {
                return resultjson.getJSONObject("data").getJSONArray("things").getJSONObject(0).getJSONObject("data");
            }
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new RedditApiException("Encoding error: "+e.getMessage());
        }
    }

    public void deleteComment(String thingId) throws RedditApiException {
        checkLogin();

        JSONObject resultjson;

        try {
            String url = OAUTH_ENDPOINT + "/api/del?id=" + thingId;

            resultjson = redditApiPost(url);

            if (resultjson.has("errors") && resultjson.getJSONArray("errors").length()>0) {
                JSONArray errors = resultjson.getJSONArray("errors");
                JSONArray firsterror = (JSONArray) errors.get(0);
                if (firsterror.get(0).equals("USER_REQUIRED")) {
                    oauthToken = null; // bearer token invalid, nullify
                    throw new RedditApiException("Authentication Error, Reddit Login Required", true); // creds invalid re-authenticate.
                }
                throw new RedditApiException("API Error: "+firsterror.get(1), true);
            }
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
    }

    public JSONArray getMySubreddits() throws RedditApiException {
        checkLogin();

        String url = OAUTH_ENDPOINT + "/subreddits/mine/subscriber.json?limit=100&show=all";
        JSONArray resultjson;
        try {
            resultjson = redditApiGet(url, true).getJSONObject("data").getJSONArray("children");
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Parsing error: "+e.getMessage());
        }
        return resultjson;
    }

    public JSONObject subscribe(String subId, boolean subscribe) throws RedditApiException {
        checkLogin();

        String url = OAUTH_ENDPOINT + "/api/subscribe?sr="+ subId +"&action="+(subscribe?"sub":"unsub");

        return redditApiPost(url);
    }

    public JSONArray getMyMultis() throws RedditApiException {
        checkLogin();

        String url = OAUTH_ENDPOINT + "/api/multi/mine";

        return redditApiGetArray(url, true);
    }

    public JSONObject copyMulti(String name, String fromPath) throws RedditApiException {
        checkLogin();

        String url;
        String toPath = "user/"+username+"/m/"+name.toLowerCase().replaceAll("\\s+", "");
        System.out.println(toPath);
        try {
            url = OAUTH_ENDPOINT + "/api/multi/copy?display_name="+ URLEncoder.encode(name, "UTF-8")+"&from="+URLEncoder.encode(fromPath, "UTF-8")+"&to="+URLEncoder.encode(toPath, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new RedditApiException("Encoding error: "+e.getMessage());
        }

        return redditApiPost(url);
    }

    public JSONObject createMulti(String name, JSONObject multiObj) throws RedditApiException {
        checkLogin();

        String url;
        try {
            url = OAUTH_ENDPOINT + "/api/multi/user/"+username+"/m/"+name+"?model="+URLEncoder.encode(multiObj.toString(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new RedditApiException("Encoding error: "+e.getMessage());
        }

        return redditApiPost(url);
    }

    public JSONObject editMulti(String multiPath, JSONObject multiObj) throws RedditApiException {
        checkLogin();

        String url;
        try {
            url = OAUTH_ENDPOINT + "/api/multi"+multiPath+"?model="+URLEncoder.encode(multiObj.toString(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new RedditApiException("Encoding error: "+e.getMessage());
        }

        return redditApiPut(url);
    }

    public void deleteMulti(String multiPath) throws RedditApiException {
        checkLogin();

        String url = OAUTH_ENDPOINT + "/api/multi"+multiPath;

        redditApiDelete(url);
    }

    public JSONObject renameMulti(String multiPath, String newName) throws RedditApiException {
        checkLogin();

        String url = OAUTH_ENDPOINT + "/api/multi/rename/?from="+multiPath+"&to=/user/"+username+"/m/"+newName;

        return redditApiPost(url);
    }

    public JSONObject addMultiSubreddit(String multiPath, String subredditName) throws RedditApiException {
        checkLogin();

        String url;
        try {
            url = OAUTH_ENDPOINT + "/api/multi"+multiPath+"/r/"+subredditName+"?srname="+URLEncoder.encode(subredditName, "UTF-8")+"&model="+URLEncoder.encode("{\"name\":\""+subredditName+"\"}", "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new RedditApiException("Encoding error: "+e.getMessage());
        }

        return redditApiPut(url);
    }

    public void removeMultiSubreddit(String multiPath, String subredditName) throws RedditApiException {
        checkLogin();

        String url;
        try {
            url = OAUTH_ENDPOINT + "/api/multi"+multiPath+"/r/"+subredditName+"?srname="+URLEncoder.encode(subredditName, "UTF-8")+"&model="+URLEncoder.encode("{\"name\":\""+subredditName+"\"}", "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new RedditApiException("Encoding error: "+e.getMessage());
        }

        redditApiDelete(url);
    }

    public JSONObject submit(String subreddit, boolean isLink, String title, String content) throws RedditApiException {
        checkLogin();

        try {
            content = URLEncoder.encode(content, "UTF-8");
            String url = OAUTH_ENDPOINT + "/api/submit?api_type=json&extension=json&then=comments&sr=" + URLEncoder.encode(subreddit, "UTF-8") + "&kind=" + (isLink?"link":"self") + "&title=" + URLEncoder.encode(title, "UTF-8") + "&" + (isLink?"url=":"text=")+content;

            return redditApiPost(url).getJSONObject("json");

        } catch (JSONException | UnsupportedEncodingException e) {
            throw new RedditApiException(e.getMessage());
        }
    }

    public void save(String category, String name) throws RedditApiException {
        checkLogin();
        String url = OAUTH_ENDPOINT + "/api/save?category="+category+"&id="+name;
        redditApiPost(url);
    }

    public void unSave(String name) throws RedditApiException {
        checkLogin();
        String url = OAUTH_ENDPOINT + "/api/unsave?id="+name;
        redditApiPost(url);
    }

    public void hide(String name) throws RedditApiException {
        checkLogin();
        String url = OAUTH_ENDPOINT + "/api/hide?id="+name;
        redditApiPost(url);
    }

    public void unHide(String name) throws RedditApiException {
        checkLogin();
        String url = OAUTH_ENDPOINT + "/api/unhide?id="+name;
        redditApiPost(url);
    }

    public JSONObject getFilter(String filter) throws RedditApiException {
        checkLogin();
        String url = OAUTH_ENDPOINT + "/api/filter/user/"+getUsername()+"/f/"+filter;
        return redditApiGet(url, true);
    }

    public JSONObject addFilterSubreddit(String filter, String subreddit) throws RedditApiException {
        checkLogin();
        try {
            String url = OAUTH_ENDPOINT + "/api/filter/user/"+getUsername()+"/f/"+filter+"/r/"+subreddit+"?model="+ URLEncoder.encode("{\"name\":\""+subreddit+"\"}", "UTF-8");

            return redditApiPut(url);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new RedditApiException("Encoding error: "+e.getMessage());
        }
    }

    public void removeFilterSubreddit(String filter, String subreddit) throws RedditApiException {
        checkLogin();
        String url = OAUTH_ENDPOINT + "/api/filter/user/"+getUsername()+"/f/"+filter+"/r/"+subreddit;
        redditApiDelete(url);
    }

    // COMM FUNCTIONS
    // Create Http/s client
    private void createHttpClient() {
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(new Interceptor() {
                @Override
                public Response intercept(Chain chain) throws IOException {
                    Request originalRequest = chain.request();
                    Request requestWithUserAgent = originalRequest.newBuilder()
                            .removeHeader("User-Agent")
                            .addHeader("User-Agent", userAgent)
                            .build();
                    return chain.proceed(requestWithUserAgent);
                }
            }).build();
    }

    private JSONArray redditApiGetArray(String url, boolean useAuth) throws RedditApiException {
        JSONArray jArr;
        try {
            String json = redditApiRequest(url, "GET", useAuth ? REQUEST_MODE_AUTHED : REQUEST_MODE_UNAUTHED, null);
            jArr = new JSONArray(json);
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Error: "+e.getMessage());
        }
        return jArr;
    }

    private JSONObject redditApiGet(String url, boolean useAuth) throws RedditApiException {
        JSONObject jObj;
        try {
            String json = redditApiRequest(url, "GET", useAuth?REQUEST_MODE_AUTHED:REQUEST_MODE_UNAUTHED, null);
            jObj = new JSONObject(json);
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Error: "+e.getMessage());
        }
        return jObj;
    }

    private JSONObject redditApiPost(String url) throws RedditApiException {
        JSONObject jObj;
        try {
            String json = redditApiRequest(url, "POST", REQUEST_MODE_AUTHED, null);
            jObj = new JSONObject(json);
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Error: "+e.getMessage());
        }
        return jObj;
    }

    private JSONObject redditApiPut(String url) throws RedditApiException {
        JSONObject jObj;
        try {
            String json = redditApiRequest(url, "PUT", REQUEST_MODE_AUTHED, null);
            jObj = new JSONObject(json);
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Error: "+e.getMessage());
        }
        return jObj;
    }

    private void redditApiDelete(String url) throws RedditApiException {

        redditApiRequest(url, "DELETE", REQUEST_MODE_AUTHED, null);
    }

    private JSONObject redditApiOauthRequest(String url, HashMap<String, String> data) throws RedditApiException {
        JSONObject jObj;
        try {
            String json = redditApiRequest(url, "POST", REQUEST_MODE_OAUTHREQ, data);
            jObj = new JSONObject(json);
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RedditApiException("Error: "+e.getMessage());
        }
        return jObj;
    }

    private static final int REQUEST_MODE_UNAUTHED = 0;
    private static final int REQUEST_MODE_AUTHED = 1;
    private static final int REQUEST_MODE_OAUTHREQ = 2;
    private static final MediaType POST_ENCODED = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");
    private String redditApiRequest(String urlStr, String method, int oauthMode, HashMap<String, String> formData) throws RedditApiException {
        String responseText;
        // create client if null
        if (httpClient == null) {
            createHttpClient();
        }
        try {
            Request.Builder httpRequest = new Request.Builder().url(urlStr);
            RequestBody httpRequestBody;
            String requestStr = "";
            if (formData!=null) {
                FormBody.Builder formBuilder = new FormBody.Builder();
                Iterator iterator = formData.keySet().iterator();
                String key;
                while (iterator.hasNext()){
                    key = (String) iterator.next();
                    formBuilder.add(key, formData.get(key));
                }
                httpRequestBody = formBuilder.build();
            } else {
                if (!method.equals("GET")) {
                    int queryIndex = urlStr.indexOf("?");
                    if (queryIndex!=-1) {
                        requestStr = URLEncoder.encode(urlStr.substring(queryIndex), "UTF-8");
                    }
                }
                httpRequestBody = RequestBody.create(POST_ENCODED, requestStr);
            }

            switch (method){
                case "POST":
                    httpRequest.post(httpRequestBody);
                    break;
                case "PUT":
                    httpRequest.put(httpRequestBody);
                    break;
                case "DELETE":
                    httpRequest.delete(httpRequestBody);
                    break;
                case "GET":
                default:
                    httpRequest.get();
                    break;
            }
            if (oauthMode==REQUEST_MODE_OAUTHREQ) {
                // For oauth token retrieval and refresh
                httpRequest.addHeader("Authorization", "Basic " + Base64.encodeToString((OAUTH_CLIENTID + ":").getBytes(), Base64.URL_SAFE | Base64.NO_WRAP));
            } else if (isLoggedIn() && oauthMode==REQUEST_MODE_AUTHED) {
                if (isTokenExpired(true)) {
                    refreshToken();
                }
                // add auth headers
                String tokenStr = getTokenValue("token_type", true) + " " + getTokenValue("access_token", true);
                httpRequest.addHeader("Authorization", tokenStr);
            } else {
                // Use app only oauth
                checkAppToken();
                // add auth headers
                String tokenStr = getTokenValue("token_type", false) + " " + getTokenValue("access_token", false);
                httpRequest.addHeader("Authorization", tokenStr);
            }

            Response response = httpClient.newCall(httpRequest.build()).execute();
            responseText = response.body().string();
            int errorCode = response.code();
            if (errorCode<200 || errorCode>202) {
                JSONObject errorJson = getErrorJson(responseText);
                String errorMsg = errorJson!=null ? getJsonErrorText(errorJson) : getHtmlErrorText(responseText);
                boolean isAuthError = errorCode==403 && isAuthenticationError(errorJson);
                if (isAuthError)
                    errorMsg += "(Permission with Reddit required)";
                throw new RedditApiException("Error "+String.valueOf(errorCode)+" "+(errorMsg.equals("")?response.message():errorMsg), isAuthError, errorCode);
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new RedditApiException("Error: "+e.getMessage());
        }

        return responseText;
    }

    private JSONObject getErrorJson(String response){
        if (response!=null) {
            if (response.indexOf("{")==0)
                try {
                    return new JSONObject(response);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
        }
        return null;
    }

    private boolean isAuthenticationError(JSONObject errorJson){
        if (errorJson!=null && errorJson.has("reason")) {
            try {
                String reason = errorJson.getString("reason");
                return reason.indexOf("OAUTH2_")==0;
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private String getJsonErrorText(JSONObject errorJson){
        String errorMsg = "";
        if (errorJson!=null) {
            try {
                if (errorJson.has("errors")) {
                    JSONArray errorArr = errorJson.getJSONArray("errors");
                    if (errorArr.length() > 0)
                        errorMsg = errorArr.getJSONArray(0).getString(1);
                } else if (errorJson.has("message")) {
                    errorMsg = errorJson.getString("message");
                    if (errorJson.has("explanation"))
                        errorMsg += ": " + errorJson.getString("explanation");
                }
                return errorMsg;
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return errorMsg;
    }

    private String getHtmlErrorText(String html){
        String errorMsg = "";
        // attempt to get html error message (often returned by 403/500)
        final Pattern patternh2 = Pattern.compile("<h2>(.+?)</h2>");
        Matcher matcher = patternh2.matcher(html);
        if (matcher.matches()) {
            errorMsg = matcher.group(1);
        }
        return errorMsg;
    }

    private void checkLogin() throws RedditApiException {
        if (!isLoggedIn()) {
            throw new RedditApiException("Reddit Login Required", true);
        }
    }

    public class RedditApiException extends Exception {
        private boolean isLoginError = false;
        private int httpErrorCode = 200;
        //Constructor that accepts a message
        public RedditApiException(String message) {
            super(message);
        }

        public RedditApiException(String message, boolean isLoginError) {
            super(message);
            this.isLoginError = isLoginError;
        }

        public RedditApiException(String message, boolean isLoginError, int httpErrorCode) {
            super(message);
            this.isLoginError = isLoginError;
            this.httpErrorCode = httpErrorCode;
        }

        @SuppressWarnings("unused")
        int getHttpErrorCode() { return httpErrorCode; }

        public boolean isAuthError(){
            return isLoginError;
        }
    }

    // OAUTH FUNCTIONS
    public boolean isLoggedIn() {
        return oauthToken != null;
    }

    private boolean isTokenExpired(boolean userToken) {
        Long now = (System.currentTimeMillis() / 1000L);
        Long expiry = (long) 0;
        try {
            expiry = userToken ?  oauthToken.getLong("expires_at") : oauthAppToken.getLong("expires_at");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return expiry < now;
    }

    private String getTokenValue(String key, boolean userToken) {
        String token = "";
        try {
            token = userToken ? oauthToken.getString(key) : oauthAppToken.getString(key);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return token;
    }

    public void retrieveToken(String code, String state) throws RedditApiException {
        if (!state.equals(oauthstate)) {
            throw new RedditApiException("OAuth Error: Invalid state");
        }
        String url = "https://www.reddit.com/api/v1/access_token";
        JSONObject resultjson;
        HashMap<String, String> params = new HashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("redirect_uri", OAUTH_REDIRECT);
        resultjson = redditApiOauthRequest(url, params);
        if (resultjson.has("access_token")) {
            // login successful, set new token and save
            oauthToken = resultjson;
            try {
                Long epoch = (System.currentTimeMillis() / 1000L);
                Long expires_at = epoch + Integer.parseInt(oauthToken.getString("expires_in"));
                oauthToken.put("expires_at", expires_at);
            } catch (JSONException e) {
                e.printStackTrace();
                throw new RedditApiException("OAuth Error: "+e.getMessage());
            }
            // try to retrieve user info & save, if exception thrown, just make sure we save token
            try {
                updateUserInfo();
            } catch (RedditApiException e) {
                e.printStackTrace();
                saveUserData();
            }
            //System.out.println("oauth request result: OK");
            return;
        }
        // throw error
        throwOAuthError(resultjson);
    }

    private void refreshToken() throws RedditApiException {
        String url = "https://www.reddit.com/api/v1/access_token";
        JSONObject resultjson;
        HashMap<String, String> params = new HashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", getTokenValue("refresh_token", true));
        resultjson = redditApiOauthRequest(url, params);
        if (resultjson.has("access_token")) {
            // login successful, update token and save
            try {
                oauthToken.put("access_token", resultjson.get("access_token"));
                oauthToken.put("token_type", resultjson.get("token_type"));
                Long expires_in = resultjson.getLong("expires_in") - 30;
                Long epoch = (System.currentTimeMillis() / 1000L);
                oauthToken.put("expires_in", expires_in);
                oauthToken.put("expires_at", (epoch + expires_in));
            } catch (JSONException e) {
                e.printStackTrace();
                throw new RedditApiException("OAuth Error: "+e.getMessage());
            }
            // save oauth token
            saveUserData();
            //System.out.println("oauth refresh result: OK");
            return;
        }
        // set error result
        throwOAuthError(resultjson);
    }

    private String getDeviceId(){
        String uuid = sharedPrefs.getString("oauthUuid", "");
        if (uuid.equals("")){
            uuid = UUID.randomUUID().toString();
            sharedPrefs.edit().putString("oauthUuid", uuid).apply();
        }
        return uuid;
    }

    private void checkAppToken() throws RedditApiException {
        if (oauthAppToken==null)
            retrieveAppToken();

        if (isTokenExpired(false))
            retrieveAppToken();
    }

    // retrieve application only oauth token, used for logged out api calls.
    private void retrieveAppToken() throws RedditApiException {
        String url = "https://www.reddit.com/api/v1/access_token";
        JSONObject resultjson;
        HashMap<String, String> params = new HashMap<>();
        params.put("grant_type", "https://oauth.reddit.com/grants/installed_client");
        params.put("device_id", getDeviceId());
        resultjson = redditApiOauthRequest(url, params);
        if (resultjson.has("access_token")) {
            // login successful, set new token and save
            oauthAppToken = resultjson;
            try {
                Long epoch = (System.currentTimeMillis() / 1000L);
                Long expires_at = epoch + Integer.parseInt(oauthAppToken.getString("expires_in"));
                oauthAppToken.put("expires_at", expires_at);
            } catch (JSONException e) {
                e.printStackTrace();
                throw new RedditApiException("OAuth Error: "+e.getMessage());
            }
            saveAppToken();
            return;
        }
        // throw error
        throwOAuthError(resultjson);
    }

    private void throwOAuthError(JSONObject resultjson) throws RedditApiException {
        String error;
        if (resultjson.has("error")){
            try {
                error = resultjson.getString("error");
            } catch (JSONException e) {
                e.printStackTrace();
                error = "Unknown Error D-:";
            }
        } else {
            error = "Unknown Error D-:";
        }
        throw new RedditApiException("OAuth Error: "+error);
    }

    private void saveAppToken(){
        sharedPrefs.edit().putString("oauthAppToken", oauthAppToken == null ? "" : oauthAppToken.toString()).apply();
    }

    private void saveUserData() {
        SharedPreferences.Editor edit = sharedPrefs.edit();
        edit.putString("oauthtoken", oauthToken == null ? "" : oauthToken.toString());
        edit.putString("user_info", userInfo.toString());
        edit.putString("username", username);
        edit.putInt("inbox_count", inboxCount);
        edit.putLong("last_info_update", lastUpdateTime);
        edit.apply();
    }

}
