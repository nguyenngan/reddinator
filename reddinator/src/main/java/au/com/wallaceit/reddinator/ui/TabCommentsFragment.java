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

package au.com.wallaceit.reddinator.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.apache.commons.lang3.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import au.com.wallaceit.reddinator.R;
import au.com.wallaceit.reddinator.Reddinator;
import au.com.wallaceit.reddinator.activity.CommentsContextDialogActivity;
import au.com.wallaceit.reddinator.activity.ViewRedditActivity;
import au.com.wallaceit.reddinator.activity.WebViewActivity;
import au.com.wallaceit.reddinator.core.RedditData;
import au.com.wallaceit.reddinator.core.Utilities;
import au.com.wallaceit.reddinator.tasks.CommentTask;
import au.com.wallaceit.reddinator.tasks.VoteTask;

public class TabCommentsFragment extends Fragment implements VoteTask.Callback, CommentTask.Callback {
    private Resources resources;
    private SharedPreferences mSharedPreferences;
    private WebView mWebView;
    private boolean webviewInit = false;
    private boolean mFirstTime = true;
    private LinearLayout ll;
    private Reddinator global;
    private String articleId;
    private String permalink;
    private String currentSort = "best";
    private CommentsLoader commentsLoader;
    private VoteTask commentsVoteTask;
    private CommentTask commentTask;

    public static TabCommentsFragment init(String id, String permalink) {
        TabCommentsFragment commentsTab = new TabCommentsFragment();
        Bundle args = new Bundle();
        args.putString("id", id);
        args.putString("permalink", permalink);
        //args.putBoolean("load", load);
        commentsTab.setArguments(args);
        return commentsTab;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

    }

    private JSONArray initialData = null;
    public void loadFromData(JSONObject postInfo, JSONArray comments){
        subData = postInfo;
        if (webviewInit) {
            populateCommentsFromData(comments.toString());
        } else {
            initialData = comments;
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context mContext = this.getActivity();
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        global = (Reddinator) mContext.getApplicationContext();
        resources = getResources();

        // get needed activity values
        articleId = getArguments().getString("id");
        permalink = getArguments().getString("permalink");
    }

    public void updateTheme() {
        String themeStr = ((ViewRedditActivity) getActivity()).getCurrentTheme().getValuesString(true);
        Utilities.executeJavascriptInWebview(mWebView, "setTheme(\"" + StringEscapeUtils.escapeEcmaScript(themeStr) + "\")");
    }

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        if (container == null) {
            return null;
        }
        if (mFirstTime) {
            ll = (LinearLayout) inflater.inflate(R.layout.webtab, container, false);
            mWebView = (WebView) ll.findViewById(R.id.webView1);
            int backgroundColor = Color.parseColor(((ViewRedditActivity) getActivity()).getCurrentTheme().getValue("background_color"));
            mWebView.setBackgroundColor(backgroundColor);
            WebSettings webSettings = mWebView.getSettings();
            webSettings.setJavaScriptEnabled(true); // enable ecmascript
            webSettings.setDomStorageEnabled(true); // some video sites require dom storage
            webSettings.setSupportZoom(false);
            webSettings.setBuiltInZoomControls(false);
            webSettings.setDisplayZoomControls(false);
            int fontSize = Integer.parseInt(mSharedPreferences.getString("commentfontpref", "18"));
            webSettings.setDefaultFontSize(fontSize);
            //webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);

            // get theme values with comments layout prefs included
            final String themeStr = global.mThemeManager.getActiveTheme("appthemepref").getValuesString(true);
            mWebView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {

                    global.handleLink(getContext(), url);
                    return true; // always override url
                }

                public void onPageFinished(WebView view, String url) {
                    mWebView.loadUrl("javascript:init(\"" + StringEscapeUtils.escapeEcmaScript(themeStr) + "\", \""+global.mRedditData.getUsername()+"\")");
                    if (initialData!=null) {
                        populateCommentsFromData(initialData.toString());
                    }
                    webviewInit = true;
                }
            });
            mWebView.setWebChromeClient(new WebChromeClient());

            mWebView.requestFocus(View.FOCUS_DOWN);
            WebInterface webInterface = new WebInterface(getActivity());
            mWebView.addJavascriptInterface(webInterface, "Reddinator");
            getActivity().registerForContextMenu(mWebView);

            mWebView.loadUrl("file:///android_asset/comments.html#"+articleId);

            mFirstTime = false;
        } else {
            ((ViewGroup) ll.getParent()).removeView(ll);
        }

        return ll;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        //mWebView.saveState(outState);
    }

    @Override
    public void onPause() {
        super.onPause();
        //mWebView.saveState(WVState);
    }

    @Override
    public void onDestroy(){
        super.onDestroy();
        if (commentsLoader!=null)
            commentsLoader.cancel(true);
        if (commentsVoteTask!=null)
            commentsVoteTask.cancel(false);
        if (commentTask!=null)
            commentTask.cancel(false);
        if (mWebView != null) {
            mWebView.removeAllViews();
            mWebView.destroy();
        }
    }

    @Override
    public void onVoteComplete(boolean result, RedditData.RedditApiException exception, String redditId, int direction, int netVote, int listPosition) {
        if (getActivity() == null)
            return;

        ((ViewRedditActivity) getActivity()).setTitleText(resources.getString(R.string.app_name)); // reset title
        if (result) {
            mWebView.loadUrl("javascript:voteCallback(\"" + redditId + "\", \"" + direction + "\", "+netVote+")");
        } else {
            // check login required
            if (exception.isAuthError()) global.mRedditData.initiateLogin(getActivity(), false);
            // show error
            Utilities.showApiErrorToastOrDialog(getActivity(), exception);
        }
    }

    @Override
    public void onCommentComplete(JSONObject result, RedditData.RedditApiException exception, int action, String redditId) {
        if (getActivity() == null)
            return;

        ((ViewRedditActivity) getActivity()).setTitleText(resources.getString(R.string.app_name)); // reset title
        if (result!=null){
            switch (action){
                case -1:
                    mWebView.loadUrl("javascript:deleteCallback(\"" + redditId + "\")");
                    break;
                case 0:
                    mWebView.loadUrl("javascript:commentCallback(\"" + redditId + "\", \"" + StringEscapeUtils.escapeEcmaScript(result.toString()) + "\")");
                    break;
                case 1:
                    mWebView.loadUrl("javascript:editCallback(\"" + redditId + "\", \"" + StringEscapeUtils.escapeEcmaScript(result.toString()) + "\")");
                    break;
            }
        } else {
            // check login required
            if (exception.isAuthError()) global.mRedditData.initiateLogin(getActivity(), false);
            // show error
            Utilities.showApiErrorToastOrDialog(getActivity(), exception);
            mWebView.loadUrl("javascript:commentCallback(\"" + redditId + "\", false)");
        }
    }

    public class WebInterface {
        Context mContext;

        /**
         * Instantiate the interface and set the context
         */
        WebInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void reloadComments(String sort) {
            loadComments(sort);
        }

        @JavascriptInterface
        public void loadChildren(String moreId, String children) {
            commentsLoader = new CommentsLoader(currentSort, moreId, children);
            commentsLoader.execute();
        }

        @JavascriptInterface
        public void vote(String thingId, int direction, int currentVote) {
            if (getActivity() == null)
                return;
            ((ViewRedditActivity) getActivity()).setTitleText(resources.getString(R.string.voting));
            commentsVoteTask = new VoteTask(global, TabCommentsFragment.this, thingId, direction, currentVote);
            commentsVoteTask.execute();
        }

        @JavascriptInterface
        public void comment(String parentId, String text) {
            if (getActivity() == null)
                return;
            ((ViewRedditActivity) getActivity()).setTitleText(resources.getString(R.string.submitting));
            commentTask = new CommentTask(global, parentId, text, 0, TabCommentsFragment.this);
            commentTask.execute();
        }

        @JavascriptInterface
        public void edit(String thingId, String text) {
            if (getActivity() == null)
                return;
            ((ViewRedditActivity) getActivity()).setTitleText(resources.getString(R.string.submitting));
            commentTask = new CommentTask(global, thingId, text, 1, TabCommentsFragment.this);
            commentTask.execute();
        }

        @JavascriptInterface
        public void delete(String thingId) {
            if (getActivity() == null)
                return;
            ((ViewRedditActivity) getActivity()).setTitleText(resources.getString(R.string.deleting));
            commentTask = new CommentTask(global, thingId, null, -1, TabCommentsFragment.this);
            commentTask.execute();
        }

        @JavascriptInterface
        public void openCommentLink(String thingId) {
            Intent intent = new Intent(mContext, WebViewActivity.class);
            intent.putExtra("url", global.getDefaultMobileSite() + permalink + thingId.substring(3));
            //System.out.println("http://www.reddit.com"+permalink+thingId+".compact");
            startActivity(intent);
        }

        @JavascriptInterface
        public void openCommentsContext(String thingId) {
            Intent intent = new Intent(mContext, CommentsContextDialogActivity.class);
            intent.setData(Uri.parse(Reddinator.REDDIT_BASE_URL + permalink + thingId + "?context=0"));
            startActivity(intent);
        }

        @JavascriptInterface
        public void archiveToast() {
            Toast.makeText(getActivity(), R.string.archived_post_error, Toast.LENGTH_LONG).show();
        }

        // TODO: replace javascript alerts and confirms with native ones
        /*@JavascriptInterface
        public void alert(String title, String message) {
            new AlertDialog.Builder(getActivity())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
        }*/
    }

    private void loadComments(String sort) {
        if (sort != null)
            currentSort = sort;
        commentsLoader = new CommentsLoader(currentSort);
        commentsLoader.execute();
    }

    private JSONObject subData;
    class CommentsLoader extends AsyncTask<Void, Integer, String> {

        private boolean loadMore = false;
        private String mSort = "best";
        private String mMoreId;
        private String mChildren;

        CommentsLoader(String sort){
            mSort = sort;
        }

        CommentsLoader(String sort, String moreId, String children) {
            mSort = sort;
            if (children != null && !children.equals("")) {
                loadMore = true;
                mMoreId = moreId;
                mChildren = children;
            }
        }

        private String lastError;
        @Override
        protected String doInBackground(Void... none) {
            JSONArray data;

            try {
                if (loadMore) {
                    data = global.mRedditData.getChildComments(mMoreId, articleId, mChildren, mSort);
                } else {
                    // reloading
                    JSONArray commentObj = global.mRedditData.getCommentsFeed(permalink, mSort, 25);
                    subData = commentObj.getJSONObject(0).getJSONObject("data").getJSONArray("children").getJSONObject(0).getJSONObject("data");
                    data = commentObj.getJSONObject(1).getJSONObject("data").getJSONArray("children");
                }
            } catch (JSONException | RedditData.RedditApiException e) {
                e.printStackTrace();
                lastError = e.getMessage();
                return "-1"; // Indicate error
            }

            if (data.length()>0) {
                return data.toString();
            }

            return "";
        }

        @Override
        protected void onPostExecute(String result) {
            switch (result) {
                case "":
                    if (!loadMore) {
                        Utilities.executeJavascriptInWebview(mWebView, "showLoadingView('" + resources.getString(R.string.no_comments_here) + "');");
                    } else {
                        Utilities.executeJavascriptInWebview(mWebView, "noChildrenCallback('"+mMoreId+"');");
                    }
                    break;
                case "-1":
                    // show error
                    if (!loadMore) {
                        Utilities.executeJavascriptInWebview(mWebView, "showLoadingView('" + resources.getString(R.string.error_loading_comments) + "');");
                    } else {
                        // reset load more button
                        Utilities.executeJavascriptInWebview(mWebView, "resetMoreClickEvent('" + mMoreId + "');");
                    }
                    if (getActivity()!=null)
                        Toast.makeText(getActivity(), lastError, Toast.LENGTH_LONG).show();
                    break;
                default:
                    if (loadMore) {
                        Utilities.executeJavascriptInWebview(mWebView, "populateChildComments(\"" + mMoreId + "\", \"" + StringEscapeUtils.escapeEcmaScript(result) + "\");");
                    } else {
                        populateCommentsFromData(result);
                    }
                    break;
            }
        }
    }

    private void populateCommentsFromData(String data){
        String author = "";
        boolean archived = false;
        try {
            author = subData.getString("author");
            archived = subData.getBoolean("archived");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if (data.equals("[]")){
            Utilities.executeJavascriptInWebview(mWebView, "showLoadingView('" + resources.getString(R.string.no_comments_here) + "');");
        } else {
            Utilities.executeJavascriptInWebview(mWebView, "populateComments(\"" + author + "\", " + archived + ", \"" + StringEscapeUtils.escapeEcmaScript(data) + "\");");
        }
    }
}
