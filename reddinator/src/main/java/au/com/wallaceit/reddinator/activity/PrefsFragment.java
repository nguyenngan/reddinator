package au.com.wallaceit.reddinator.activity;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.view.MenuItem;
import android.webkit.CookieManager;
import android.widget.Toast;

import net.rdrei.android.dirchooser.DirectoryChooserConfig;
import net.rdrei.android.dirchooser.DirectoryChooserFragment;

import java.util.LinkedHashMap;

import au.com.wallaceit.reddinator.R;
import au.com.wallaceit.reddinator.Reddinator;
import au.com.wallaceit.reddinator.core.ThemeManager;
import au.com.wallaceit.reddinator.core.Utilities;
import au.com.wallaceit.reddinator.service.MailCheckReceiver;
import au.com.wallaceit.reddinator.service.WidgetCommon;
import au.com.wallaceit.reddinator.tasks.SyncUserDataTask;

public class PrefsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener, DirectoryChooserFragment.OnFragmentInteractionListener {
    public int mAppWidgetId;
    private SharedPreferences mSharedPreferences;
    private String mRefreshrate = "";
    private String mTitleFontSize = "";
    private String mAppTheme = "";
    private String mMailRefresh = "";
    boolean isfromappview = false;
    private Reddinator global;
    private boolean themeChanged = false;
    private PreferenceCategory appearanceCat;
    private ListPreference themePref;
    private Preference themeEditorButton;
    private Preference clearCookiesBtn;
    private String curDownloadPath;
    private Preference downloadLocationBtn;
    private DirectoryChooserFragment mDialog;
    @Override
    public void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        ((PrefsActivity) getActivity()).getListView().setBackgroundColor(Color.WHITE);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        global = ((Reddinator) getActivity().getApplicationContext());
        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences);

        if (global.mRedditData.isLoggedIn()){
            // Load the account preferences when logged in
            addPreferencesFromResource(R.xml.account_preferences);
            Preference logoutbtn = findPreference("logout");
            final PreferenceCategory accountSettings = (PreferenceCategory) findPreference("account");
            logoutbtn.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    // clear oauth token, userdata, webview cookies and load default subreddits
                    global.mRedditData.purgeAccountData();
                    global.getSubredditManager().clearMultis();
                    global.getSubredditManager().loadDefaultSubreddits();
                    global.getSubredditManager().clearAllFilter();
                    clearWebviewCookies();
                    // remove mail check alarm
                    MailCheckReceiver.setAlarm(getActivity());
                    // remove account prefs screen
                    getPreferenceScreen().removePreference(accountSettings);
                    Toast.makeText(getActivity(), getResources().getString(R.string.account_disconnected), Toast.LENGTH_LONG).show();
                    getActivity().setResult(7);
                    return true;
                }
            });
            Preference refreshbtn = findPreference("refresh_data");
            refreshbtn.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    new SyncUserDataTask(getActivity(), null, true, 0).execute();
                    return false;
                }
            });
        }

        addPreferencesFromResource(R.xml.utility_preferences);

        appearanceCat = (PreferenceCategory) findPreference("appearance");

        Preference themeManagerButton = findPreference("theme_manager_button");
        themeManagerButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = new Intent(getActivity(), ThemesActivity.class);
                startActivityForResult(intent, ThemesActivity.REQUEST_CODE_NO_WIDGET_UPDATES);
                return true;
            }
        });

        themeEditorButton = findPreference("theme_editor_button");
        themeEditorButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = new Intent(getActivity(), ThemeEditorActivity.class);
                intent.putExtra("themeId", mAppTheme);
                startActivityForResult(intent, ThemesActivity.REQUEST_CODE_NO_WIDGET_UPDATES);
                return true;
            }
        });

        Preference clearFilterButton = findPreference("clear_post_filter");
        if (!global.mRedditData.isLoggedIn()) {
            clearFilterButton.setSummary(getString(R.string.clear_post_filter_summary, global.getSubredditManager().getPostFilterCount()));
            clearFilterButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    global.getSubredditManager().clearPostFilters();
                    Toast.makeText(getActivity(), getString(R.string.clear_post_filter_message), Toast.LENGTH_LONG).show();
                    return true;
                }
            });
        } else {
            clearFilterButton.setSummary(getString(R.string.clear_post_filter_summary_disabled));
            clearFilterButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent intent = new Intent(getActivity(), AccountActivity.class);
                    intent.setAction(AccountActivity.ACTION_HIDDEN);
                    startActivity(intent);
                    getActivity().finish();
                    return true;
                }
            });
        }

        clearCookiesBtn = findPreference("clear_cookies");
        clearCookiesBtn.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                clearWebviewCookies();
                Toast.makeText(getActivity(), getResources().getString(R.string.cookies_cleared), Toast.LENGTH_LONG).show();
                return true;
            }
        });

        final Preference clearImageCacheBtn = findPreference("clear_image_cache");
        clearImageCacheBtn.setSummary(getString(R.string.clear_image_cache_summary, Utilities.getImageCacheSize(getActivity())));
        clearImageCacheBtn.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                global.clearImageCache(0);
                clearImageCacheBtn.setSummary(getString(R.string.clear_image_cache_summary, Utilities.getImageCacheSize(getActivity())));
                Toast.makeText(getActivity(), getResources().getString(R.string.image_cache_cleared), Toast.LENGTH_LONG).show();
                clearImageCacheBtn.setEnabled(false);
                return true;
            }
        });

        final Preference clearFeedDataBtn = findPreference("clear_feed_data");
        clearFeedDataBtn.setSummary(getString(R.string.clear_feed_data_summary, Utilities.getFeedDataSize(getActivity())));
        clearFeedDataBtn.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                global.clearFeedData();
                clearFeedDataBtn.setSummary(getString(R.string.clear_feed_data_summary, Utilities.getFeedDataSize(getActivity())));
                Toast.makeText(getActivity(), getResources().getString(R.string.feed_data_cleared), Toast.LENGTH_LONG).show();
                clearFeedDataBtn.setEnabled(false);
                return true;
            }
        });

        curDownloadPath = mSharedPreferences.getString("download_location", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
        downloadLocationBtn = findPreference("download_location");
        downloadLocationBtn.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                DirectoryChooserConfig config = DirectoryChooserConfig.builder()
                        .allowNewDirectoryNameModification(true)
                        .newDirectoryName("")
                        .initialDirectory(curDownloadPath).build();
                mDialog = DirectoryChooserFragment.newInstance(config);
                mDialog.setDirectoryChooserListener(PrefsFragment.this);
                mDialog.show(getFragmentManager(), null);
                return false;
            }
        });
        downloadLocationBtn.setSummary(curDownloadPath);

        themePref = (ListPreference) findPreference("appthemepref");

        mSharedPreferences.registerOnSharedPreferenceChangeListener(PrefsFragment.this);
    }

    private void clearWebviewCookies(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().removeAllCookies(null);
        } else {
            //noinspection deprecation
            CookieManager.getInstance().removeAllCookie();
        }
        clearCookiesBtn.setEnabled(false);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        switch(key){
            case "appthemepref":
                setupThemePrefs();
            case "logoopenpref":
            case "commentslayoutpref":
            case "commentsborderpref":
                themeChanged = true;
                break;
        }
    }

    @Override
    public void onDestroy(){
        super.onDestroy();
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(PrefsFragment.this);
    }

    @Override
    public void onResume() {
        super.onResume();
        Intent intent = getActivity().getIntent();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            isfromappview = !intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID);
            if (!isfromappview) {
                mAppWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            }
        }
        mRefreshrate = mSharedPreferences.getString(getString(R.string.refresh_rate_pref), "43200000");
        mTitleFontSize = mSharedPreferences.getString(getString(R.string.title_font_pref), "16");
        setupThemePrefs();

        mMailRefresh = mSharedPreferences.getString(getString(R.string.background_mail_pref), "43200000");

        // set themes list
        LinkedHashMap<String, String> themeList = global.mThemeManager.getThemeList(ThemeManager.LISTMODE_ALL);
        themePref.setEntries(themeList.values().toArray(new CharSequence[themeList.values().size()]));
        themePref.setEntryValues(themeList.keySet().toArray(new CharSequence[themeList.keySet().size()]));

        //Toast.makeText(this, "Press the back button to save settings", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data){
        if (resultCode==6) {
            themeChanged = true;
        }
    }

    private void setupThemePrefs(){
        mAppTheme = mSharedPreferences.getString(getString(R.string.app_theme_pref), "reddit_classic");
        if (global.mThemeManager.isThemeEditable(mAppTheme)){
            appearanceCat.addPreference(themeEditorButton);
        } else {
            appearanceCat.removePreference(themeEditorButton);
        }
    }

    public void onBackPressed() {
        saveSettingsAndFinish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case android.R.id.home:
                saveSettingsAndFinish();
                return true;
        }
        return false;
    }

    private void saveSettingsAndFinish() {
        // check if refresh rate has changed and update if needed
        if (!mRefreshrate.equals(mSharedPreferences.getString(getString(R.string.refresh_rate_pref), "43200000"))) {
            //System.out.println("Refresh preference changed, updating alarm");
            WidgetCommon.setUpdateSchedule(getActivity());
        }
        // check if background mail check interval has changed
        if (!mMailRefresh.equals(mSharedPreferences.getString(getString(R.string.background_mail_pref), "43200000"))) {
            //System.out.println("Refresh preference changed, updating alarm");
            MailCheckReceiver.setAlarm(getActivity());
        }
        // check if theme or style has changed and update if needed
        if (themeChanged || !mTitleFontSize.equals(mSharedPreferences.getString(getString(R.string.title_font_pref), "16"))) {
            // if we are returning to app view,set the result intent, indicating a theme update is needed
            Intent intent = new Intent();
            intent.putExtra("themeupdate", true);
            getActivity().setResult(6, intent);
            if (getActivity().getIntent().getIntExtra("requestCode", 0)!=ThemesActivity.REQUEST_CODE_NO_WIDGET_UPDATES) {
                Reddinator global = ((Reddinator) getActivity().getApplicationContext());
                if (global != null) {
                    WidgetCommon.refreshAllWidgetViews(global);
                }
            }
        }

        getActivity().finish();
    }

    @Override
    public void onSelectDirectory(@NonNull String path) {
        mSharedPreferences.edit().putString("download_location", path).apply();
        curDownloadPath = path;
        downloadLocationBtn.setSummary(path);
        mDialog.dismiss();
    }

    @Override
    public void onCancelChooser() {
        mDialog.dismiss();
    }
}