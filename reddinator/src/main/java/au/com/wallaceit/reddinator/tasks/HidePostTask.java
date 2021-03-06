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
 *
 * Created by michael on 1/02/16.
 */
package au.com.wallaceit.reddinator.tasks;

import android.content.Context;
import android.os.AsyncTask;
import android.widget.Toast;

import au.com.wallaceit.reddinator.Reddinator;
import au.com.wallaceit.reddinator.core.RedditData;
import au.com.wallaceit.reddinator.core.Utilities;

public class HidePostTask extends AsyncTask<String, Long, Boolean> {
    private Reddinator global;
    private RedditData.RedditApiException exception;
    private Context context;
    private Runnable callback;
    private boolean fromWidget = false;

    public HidePostTask(Context context, boolean fromWidget, Runnable callback){
        this.context = context;
        this.fromWidget = fromWidget;
        this.callback = callback;
        global = (Reddinator) context.getApplicationContext();
    }

    @Override
    protected Boolean doInBackground(String... params) {
        try {
            if (params[0].equals("unhide")){
                global.mRedditData.unHide(params[1]);
            } else {
                global.mRedditData.hide(params[0]);
            }
        } catch (RedditData.RedditApiException e) {
            e.printStackTrace();
            exception = e;
            return false;
        }
        return true;
    }

    @Override
    protected void onPostExecute(Boolean result) {
        if (!result){
            // check login required
            if (exception.isAuthError()) global.mRedditData.initiateLogin(context, fromWidget);
            // show error
            Utilities.showApiErrorToastOrDialog(context, exception);
        }
        if (callback!=null)
            callback.run();
    }
}
