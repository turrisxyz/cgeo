package cgeo.geocaching;

import cgeo.geocaching.activity.AbstractBottomNavigationActivity;
import cgeo.geocaching.activity.ActivityMixin;
import cgeo.geocaching.address.AndroidGeocoder;
import cgeo.geocaching.connector.ConnectorFactory;
import cgeo.geocaching.connector.capability.IAvatar;
import cgeo.geocaching.connector.capability.ILogin;
import cgeo.geocaching.databinding.MainActivityBinding;
import cgeo.geocaching.downloader.DownloaderUtils;
import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.location.Units;
import cgeo.geocaching.maps.mapsforge.v6.RenderThemeHelper;
import cgeo.geocaching.network.HtmlImage;
import cgeo.geocaching.permission.PermissionGrantedCallback;
import cgeo.geocaching.permission.PermissionHandler;
import cgeo.geocaching.permission.PermissionRequestContext;
import cgeo.geocaching.search.SuggestionsAdapter;
import cgeo.geocaching.sensors.GeoData;
import cgeo.geocaching.sensors.GeoDirHandler;
import cgeo.geocaching.sensors.GnssStatusProvider;
import cgeo.geocaching.sensors.GnssStatusProvider.Status;
import cgeo.geocaching.sensors.Sensors;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.settings.SettingsActivity;
import cgeo.geocaching.storage.DataStore;
import cgeo.geocaching.storage.LocalStorage;
import cgeo.geocaching.storage.extension.FoundNumCounter;
import cgeo.geocaching.ui.TextParam;
import cgeo.geocaching.ui.WeakReferenceHandler;
import cgeo.geocaching.ui.dialog.Dialogs;
import cgeo.geocaching.ui.dialog.SimpleDialog;
import cgeo.geocaching.utils.AndroidRxUtils;
import cgeo.geocaching.utils.BackupUtils;
import cgeo.geocaching.utils.ContextLogger;
import cgeo.geocaching.utils.DebugUtils;
import cgeo.geocaching.utils.Formatter;
import cgeo.geocaching.utils.Log;
import cgeo.geocaching.utils.ProcessUtils;
import cgeo.geocaching.utils.Version;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Address;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.SearchView.OnQueryTextListener;
import androidx.appcompat.widget.SearchView.OnSuggestionListener;

import java.util.ArrayList;
import java.util.List;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.functions.Consumer;
import org.apache.commons.lang3.StringUtils;

public class MainActivity extends AbstractBottomNavigationActivity {

    private static final String STATE_BACKUPUTILS = "backuputils";

    private MainActivityBinding binding;

    /**
     * view of the action bar search
     */
    private SearchView searchView;
    private MenuItem searchItem;
    private Geopoint addCoords = null;
    private boolean initialized = false;
    private boolean restoreMessageShown = false;

    private final UpdateLocation locationUpdater = new UpdateLocation();
    private final Handler updateUserInfoHandler = new UpdateUserInfoHandler(this);
    /**
     * initialization with an empty subscription
     */
    private final CompositeDisposable resumeDisposables = new CompositeDisposable();

    private BackupUtils backupUtils = null;


    private static final class UpdateUserInfoHandler extends WeakReferenceHandler<MainActivity> {

        UpdateUserInfoHandler(final MainActivity activity) {
            super(activity);
        }

        @Override
        public void handleMessage(final Message msg) {
            final MainActivity activity = getReference();
            if (activity != null) {
                // Get active connectors with login status
                final ILogin[] loginConns = ConnectorFactory.getActiveLiveConnectors();

                // Update UI
                activity.binding.connectorstatusArea.setAdapter(new ArrayAdapter<ILogin>(activity, R.layout.main_activity_connectorstatus, loginConns) {
                    @Override
                    public View getView(final int position, final View convertView, @NonNull final android.view.ViewGroup parent) {
                        View view = convertView;

                        if (view == null) {
                            view = activity.getLayoutInflater().inflate(R.layout.main_activity_connectorstatus, parent, false);
                        }

                        final ILogin connector = getItem(position);
                        fillView(view, connector);
                        return view;

                    }

                    private void fillView(final View connectorInfo, final ILogin conn) {

                        final ImageView userAvartar = connectorInfo.findViewById(R.id.item_icon);
                        final TextView userName = connectorInfo.findViewById(R.id.item_title);
                        final TextView userFounds = connectorInfo.findViewById(R.id.item_info);
                        final TextView connectorStatus = connectorInfo.findViewById(R.id.item_status);

                        final StringBuilder connInfo = new StringBuilder(conn.getNameAbbreviated()).append(Formatter.SEPARATOR).append(conn.getLoginStatusString());
                        final StringBuilder userFoundCount = new StringBuilder();

                        final int count = FoundNumCounter.getAndUpdateFoundNum(conn);
                        if (count >= 0) {
                            userFoundCount.append(activity.getResources().getQuantityString(R.plurals.user_finds, count, count));

                            if (Settings.isDisplayOfflineLogsHomescreen()) {
                                final int offlinefounds = DataStore.getFoundsOffline(conn);
                                if (offlinefounds > 0) {
                                    userFoundCount.append(" + ").append(activity.getResources().getQuantityString(R.plurals.user_finds_offline, offlinefounds, offlinefounds));
                                }
                            }
                        }

                        userName.setText(FoundNumCounter.getNotBlankUserName(conn));

                        connectorStatus.setText(connInfo);
                        connectorStatus.setOnClickListener(v -> SettingsActivity.openForScreen(R.string.preference_screen_services, activity));

                        if (userFoundCount.toString().isEmpty()) {
                            userFounds.setVisibility(View.GONE);
                        } else {
                            userFounds.setVisibility(View.VISIBLE);
                            userFounds.setText(userFoundCount);
                            userFounds.setOnClickListener(v -> {
                                activity.startActivity(CacheListActivity.getHistoryIntent(activity));
                                ActivityMixin.overrideTransitionToFade(activity);
                                activity.finish();
                            });
                        }

                        if (conn instanceof IAvatar && StringUtils.isNotBlank(Settings.getAvatarUrl((IAvatar) conn))) {
                            userAvartar.setVisibility(View.INVISIBLE);

                            // images are cached by the HtmlImage class
                            final HtmlImage imgGetter = new HtmlImage(HtmlImage.SHARED, false, false, false);
                            AndroidRxUtils.andThenOnUi(AndroidRxUtils.networkScheduler,
                                    () -> imgGetter.getDrawable(Settings.getAvatarUrl((IAvatar) conn)),
                                    img -> {
                                        userAvartar.setVisibility(View.VISIBLE);
                                        userAvartar.setImageDrawable(img);
                                    });
                        } else {
                            userAvartar.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }
    }

    private class UpdateLocation extends GeoDirHandler {

        @Override
        @SuppressLint("SetTextI18n")
        public void updateGeoData(final GeoData geo) {

            binding.navType.setText(res.getString(geo.getLocationProvider().resourceId));

            if (geo.getAccuracy() >= 0) {
                final int speed = Math.round(geo.getSpeed()) * 60 * 60 / 1000;
                binding.navAccuracy.setText("±" + Units.getDistanceFromMeters(geo.getAccuracy()) + Formatter.SEPARATOR + Units.getSpeed(speed));
            } else {
                binding.navAccuracy.setText(null);
            }

            final Geopoint currentCoords = geo.getCoords();
            if (Settings.isShowAddress()) {
                if (addCoords == null) {
                    binding.navLocation.setText(R.string.loc_no_addr);
                }
                if (addCoords == null || currentCoords.distanceTo(addCoords) > 0.5) {
                    addCoords = currentCoords;
                    final Single<String> address = (new AndroidGeocoder(MainActivity.this).getFromLocation(currentCoords)).map(MainActivity::formatAddress).onErrorResumeWith(Single.just(currentCoords.toString()));
                    AndroidRxUtils.bindActivity(MainActivity.this, address)
                            .subscribeOn(AndroidRxUtils.networkScheduler)
                            .subscribe(address12 -> binding.navLocation.setText(address12));
                }
            } else {
                binding.navLocation.setText(currentCoords.toString());
            }
        }
    }

    private final Consumer<GnssStatusProvider.Status> satellitesHandler = new Consumer<Status>() {
        @Override
        @SuppressLint("SetTextI18n")
        public void accept(final Status gnssStatus) {
            if (gnssStatus.gnssEnabled) {
                binding.navSatellites.setText(res.getString(R.string.loc_sat) + ": " + gnssStatus.satellitesFixed + '/' + gnssStatus.satellitesVisible);
            } else {
                binding.navSatellites.setText(res.getString(R.string.loc_gps_disabled));
            }
        }
    };

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        try (ContextLogger cLog = new ContextLogger(Log.LogLevel.DEBUG, "MainActivity.onCreate")) {
           // don't call the super implementation with the layout argument, as that would set the wrong theme
            super.onCreate(savedInstanceState);

            backupUtils = new BackupUtils(this, savedInstanceState == null ? null : savedInstanceState.getBundle(STATE_BACKUPUTILS));
            cLog.add("bu");

            //check database
            final String errorMsg = DataStore.initAndCheck(false);
            if (errorMsg != null) {
                DebugUtils.askUserToReportProblem(this, "Fatal DB error: " + errorMsg);
            }
            cLog.add("ds");

            binding = MainActivityBinding.inflate(getLayoutInflater());

            // init BottomNavigationController to add the bottom navigation to the layout
            setContentView(binding.getRoot());

            if ((getIntent().getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != 0) {
                // If we had been open already, start from the last used activity.
                finish();
                return;
            }

            setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL); // type to search

            Log.i("Starting " + getPackageName() + ' ' + Version.getVersionCode(this) + " a.k.a " + Version.getVersionName(this));

            PermissionHandler.requestStoragePermission(this, new PermissionGrantedCallback(PermissionRequestContext.MainActivityStorage) {
                @Override
                protected void execute() {
                    PermissionHandler.executeIfLocationPermissionGranted(MainActivity.this, new PermissionGrantedCallback(PermissionRequestContext.MainActivityOnCreate) {
                        // TODO: go directly into execute if the device api level is below 26
                        @Override
                        public void execute() {
                            final Sensors sensors = Sensors.getInstance();
                            sensors.setupGeoDataObservables(Settings.useGooglePlayServices(), Settings.useLowPowerMode());
                            sensors.setupDirectionObservable();

                            // Attempt to acquire an initial location before any real activity happens.
                            sensors.geoDataObservable(true).subscribeOn(AndroidRxUtils.looperCallbacksScheduler).take(1).subscribe();
                        }
                    });
                }
            });
            cLog.add("ph");

            init();
            cLog.add("init");

            LocalStorage.initGeocacheDataDir();
            if (LocalStorage.isRunningLowOnDiskSpace()) {
                SimpleDialog.of(this).setTitle(R.string.init_low_disk_space).setMessage(R.string.init_low_disk_space_message).show();
            }
            cLog.add("ls");

            confirmDebug();

            binding.infoNotloggedin.setOnClickListener(v ->
                SimpleDialog.of(this).setTitle(R.string.warn_notloggedin_title).setMessage(R.string.warn_notloggedin_long).setButtons(SimpleDialog.ButtonTextSet.YES_NO).confirm((dialog, which) -> SettingsActivity.openForScreen(R.string.preference_screen_services, this)));

            binding.locationArea.setOnClickListener(v -> openNavSettings());

            //do file migrations if necessary
            LocalStorage.migrateLocalStorage(this);
            cLog.add("mls");

            //sync map Theme folder
            RenderThemeHelper.resynchronizeOrDeleteMapThemeFolder();
            cLog.add("rth");

            // automated update check
            DownloaderUtils.checkForRoutingTileUpdates(this);
            cLog.add("rtu");

            DownloaderUtils.checkForMapUpdates(this);
            cLog.add("mu");
        }

        if (Log.isEnabled(Log.LogLevel.DEBUG)) {
            binding.getRoot().post(() -> Log.d("Post after MainActivity.onCreate"));
        }

    }

    private void init() {
        if (initialized) {
            return;
        }

        initialized = true;

        checkRestore();
        DataStore.cleanIfNeeded(this);
    }

    @Override
    protected void initHomeAsUpIndicator() {
        // Show c:geo logo for this activity
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setHomeAsUpIndicator(R.drawable.cgeo_actionbar_squircle);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBundle(STATE_BACKUPUTILS, backupUtils.getState());
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            PermissionHandler.executeCallbacksFor(permissions);
        } else {
            final Activity activity = this;
            final PermissionRequestContext perm = PermissionRequestContext.fromRequestCode(requestCode);
            Dialogs.newBuilder(this)
                    .setMessage(perm.getAskAgainResource())
                    .setCancelable(false)
                    .setPositiveButton(R.string.ask_again, (dialog, which) -> PermissionHandler.askAgainFor(permissions, activity, perm))
                    .setNegativeButton(R.string.close_app, (dialog, which) -> {
                        activity.finish();
                        System.exit(0);
                    })
                    .setIcon(R.drawable.ic_menu_preferences)
                    .create()
                    .show();
        }
    }

    private void confirmDebug() {
        if (Settings.isDebug() && !BuildConfig.DEBUG) {
            SimpleDialog.of(this).setTitle(R.string.init_confirm_debug).setMessage(R.string.list_confirm_debug_message).setButtons(SimpleDialog.ButtonTextSet.YES_NO).confirm((dialog, whichButton) -> Settings.setDebug(false));
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        init();
    }

    @Override
    public void onResume() {
        try (ContextLogger cLog = new ContextLogger(Log.LogLevel.DEBUG, "MainActivity.onResume")) {

            super.onResume();

            PermissionHandler.requestStoragePermission(this, new PermissionGrantedCallback(PermissionRequestContext.MainActivityStorage) {
                @Override
                protected void execute() {
                    PermissionHandler.executeIfLocationPermissionGranted(MainActivity.this, new PermissionGrantedCallback(PermissionRequestContext.MainActivityOnResume) {

                        @Override
                        public void execute() {
                            resumeDisposables.add(locationUpdater.start(GeoDirHandler.UPDATE_GEODATA | GeoDirHandler.LOW_POWER));
                            resumeDisposables.add(Sensors.getInstance().gpsStatusObservable().observeOn(AndroidSchedulers.mainThread()).subscribe(satellitesHandler));

                        }
                    });
                }
            });

            updateUserInfoHandler.sendEmptyMessage(-1);
            cLog.add("perm");

            init();
        }

        if (Log.isEnabled(Log.LogLevel.DEBUG)) {
            binding.getRoot().post(() -> Log.d("Post after MainActivity.onResume"));
        }
    }

    @Override
    public void onDestroy() {
        initialized = false;

        super.onDestroy();
    }

    @Override
    public void onStop() {
        initialized = false;
        super.onStop();
    }

    @Override
    public void onPause() {
        initialized = false;
        resumeDisposables.clear();

        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        try (ContextLogger ignore = new ContextLogger(Log.LogLevel.DEBUG, "MainActivity.onCreateOptionsMenu")) {

            getMenuInflater().inflate(R.menu.main_activity_options, menu);
            final SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
            searchItem = menu.findItem(R.id.menu_gosearch);
            searchView = (SearchView) searchItem.getActionView();
            searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
            searchView.setSuggestionsAdapter(new SuggestionsAdapter(this));
            searchView.setSubmitButtonEnabled(true);

            hideKeyboardOnSearchClick();
            hideActionIconsWhenSearchIsActive(menu);
        }

        if (Log.isEnabled(Log.LogLevel.DEBUG)) {
            binding.getRoot().post(() -> Log.d("Post after MainActivity.onCreateOptionsMenu"));
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            startActivity(new Intent(this, AboutActivity.class));
        }
        return true;
    }

    private void hideActionIconsWhenSearchIsActive(final Menu menu) {
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {

            @Override
            public boolean onMenuItemActionExpand(final MenuItem item) {
                for (int i = 0; i < menu.size(); i++) {
                    if (menu.getItem(i).getItemId() != R.id.menu_gosearch) {
                        menu.getItem(i).setVisible(false);
                    }
                }
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(final MenuItem item) {
                invalidateOptionsMenu();
                return true;
            }
        });
    }

    private void hideKeyboardOnSearchClick() {
        searchView.setOnSuggestionListener(new OnSuggestionListener() {

            @Override
            public boolean onSuggestionSelect(final int position) {
                return false;
            }

            @Override
            public boolean onSuggestionClick(final int position) {
                // needs to run delayed, as it will otherwise change the SuggestionAdapter cursor which results in inconsistent datasets (see #11803)
                searchView.postDelayed(() -> {
                    searchItem.collapseActionView();
                    searchView.setIconified(true);
                }, 1000);

                // return false to invoke standard behavior of launching the intent for the search result
                return false;
            }
        });

        // Used to collapse searchBar on submit from virtual keyboard
        searchView.setOnQueryTextListener(new OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(final String s) {
                searchItem.collapseActionView();
                searchView.setIconified(true);
                return false;
            }

            @Override
            public boolean onQueryTextChange(final String s) {
                ((SuggestionsAdapter) searchView.getSuggestionsAdapter()).changeQuery(s);
                return true;
            }
        });
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);  // call super to make lint happy
        if (backupUtils.onActivityResult(requestCode, resultCode, intent)) {
            return;
        }
        if (requestCode == Intents.SETTINGS_ACTIVITY_REQUEST_CODE) {
            if (resultCode == SettingsActivity.RESTART_NEEDED) {
                ProcessUtils.restartApplication(this);
            }
        } else {
            final IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
            if (scanResult != null) {
                final String scan = scanResult.getContents();
                if (StringUtils.isBlank(scan)) {
                    return;
                }
                SearchActivity.startActivityScan(scan, this);
            } else if (requestCode == Intents.SEARCH_REQUEST_CODE) {
                // SearchActivity activity returned without making a search
                if (resultCode == RESULT_CANCELED) {
                    String query = intent.getStringExtra(SearchManager.QUERY);
                    if (query == null) {
                        query = "";
                    }
                    SimpleDialog.of(this).setMessage(TextParam.text(res.getString(R.string.unknown_scan) + "\n\n" + query)).show();
                }
            }
        }
    }

    private void checkRestore() {

        if (DataStore.isNewlyCreatedDatebase() && !restoreMessageShown) {

            if (BackupUtils.hasBackup(BackupUtils.newestBackupFolder())) {

                restoreMessageShown = true;
                Dialogs.newBuilder(this)
                        .setTitle(res.getString(R.string.init_backup_restore))
                        .setMessage(res.getString(R.string.init_restore_confirm))
                        .setCancelable(false)
                        .setPositiveButton(getString(android.R.string.yes), (dialog, id) -> {
                            dialog.dismiss();
                            DataStore.resetNewlyCreatedDatabase();
                            backupUtils.restore(BackupUtils.newestBackupFolder());
                        })
                        .setNegativeButton(getString(android.R.string.no), (dialog, id) -> {
                            dialog.cancel();
                            DataStore.resetNewlyCreatedDatabase();
                        })
                        .create()
                        .show();
            }
        }
    }

    @Nullable
    @Override
    protected Handler getUpdateUserInfoHandler() {
        return updateUserInfoHandler;
    }

    /**
     * if no connector can log in, set visibility of warning message accordingly
     */
    @Override
    protected void onLoginIssue(final boolean issue) {
        if (issue) {
            binding.infoNotloggedinIcon.attributeImage.setImageResource(R.drawable.attribute_wirelessbeacon);
            binding.infoNotloggedinIcon.attributeStrikethru.setVisibility(View.VISIBLE);
            binding.infoNotloggedin.setVisibility(View.VISIBLE);
        } else {
            binding.infoNotloggedin.setVisibility(View.GONE);
        }
    }

    private static String formatAddress(final Address address) {
        final List<String> addressParts = new ArrayList<>();

        final String countryName = address.getCountryName();
        if (countryName != null) {
            addressParts.add(countryName);
        }
        final String locality = address.getLocality();
        if (locality != null) {
            addressParts.add(locality);
        } else {
            final String adminArea = address.getAdminArea();
            if (adminArea != null) {
                addressParts.add(adminArea);
            }
        }
        return StringUtils.join(addressParts, ", ");
    }

    public void openNavSettings() {
        startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }

    @Override
    public void onBackPressed() {
        // back may exit the app instead of closing the search action bar
        if (searchView != null && !searchView.isIconified()) {
            searchView.setIconified(true);
            searchItem.collapseActionView();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public int getSelectedBottomItemId() {
        return MENU_NOTHING_SELECTED;
    }
}
