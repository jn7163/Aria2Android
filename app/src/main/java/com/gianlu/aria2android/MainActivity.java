package com.gianlu.aria2android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.gianlu.aria2lib.Aria2Ui;
import com.gianlu.aria2lib.BadEnvironmentException;
import com.gianlu.aria2lib.Interface.Aria2ConfigurationScreen;
import com.gianlu.aria2lib.Interface.ConfigEditorActivity;
import com.gianlu.aria2lib.Interface.DownloadBinActivity;
import com.gianlu.aria2lib.Internal.Message;
import com.gianlu.commonutils.Analytics.AnalyticsApplication;
import com.gianlu.commonutils.AskPermission;
import com.gianlu.commonutils.CommonUtils;
import com.gianlu.commonutils.Dialogs.ActivityWithDialog;
import com.gianlu.commonutils.FileUtil;
import com.gianlu.commonutils.Logging;
import com.gianlu.commonutils.Preferences.Prefs;
import com.gianlu.commonutils.Toaster;
import com.google.android.material.bottomappbar.BottomAppBar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class MainActivity extends ActivityWithDialog implements Aria2Ui.Listener {
    private static final int STORAGE_ACCESS_CODE = 1;
    private FloatingActionButton toggleServer;
    private Aria2ConfigurationScreen screen;
    private Aria2Ui aria2;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == STORAGE_ACCESS_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Uri uri = data.getData();
                if (uri != null) {
                    screen.setOutputPathValue(FileUtil.getFullPathFromTreeUri(uri, this));
                    getContentResolver().takePersistableUriPermission(uri,
                            data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (aria2 != null) aria2.bind();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (aria2 != null) aria2.unbind();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (aria2 != null) aria2.askForStatus();
        screen.refreshCustomOptionsNumber();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!CommonUtils.isARM() && !Prefs.getBoolean(PK.CUSTOM_BIN)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.archNotSupported)
                    .setMessage(R.string.archNotSupported_message)
                    .setOnDismissListener(dialog -> finish())
                    .setOnCancelListener(dialog -> finish())
                    .setNeutralButton(R.string.importBin, (dialog, which) -> startDownloadBin(true))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> finish());

            showDialog(builder);
            return;
        }

        try {
            aria2 = new Aria2Ui(this, this);
            aria2.loadEnv();
        } catch (BadEnvironmentException ex) {
            Logging.log(ex);
            startDownloadBin(false);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        BottomAppBar bar = findViewById(R.id.main_bottomAppBar);
        setSupportActionBar(bar);

        screen = findViewById(R.id.main_preferences);
        screen.setup(new Aria2ConfigurationScreen.OutputPathSelector(this, STORAGE_ACCESS_CODE), PK.START_AT_BOOT, true);

        toggleServer = findViewById(R.id.main_toggleServer);
        toggleServer.setOnClickListener(view -> {
            Boolean b = (Boolean) view.getTag();
            if (b == null) {
                view.setTag(false);
                b = false;
            }

            toggleService(!b);
        });


        TextView version = findViewById(R.id.main_version);
        try {
            version.setText(aria2.version());
        } catch (BadEnvironmentException | IOException ex) {
            version.setText(R.string.unknown);
            Logging.log(ex);
        }

        if (Prefs.getBoolean(PK.IS_NEW_BUNDLED_WITH_ARIA2APP, true)) {
            showDialog(new AlertDialog.Builder(this)
                    .setTitle(R.string.useNewAria2AppInstead)
                    .setMessage(R.string.useNewAria2AppInstead_message)
                    .setNeutralButton(android.R.string.ok, null));

            Prefs.putBoolean(PK.IS_NEW_BUNDLED_WITH_ARIA2APP, false);
        }
    }

    private void toggleService(boolean on) {
        boolean successful;
        if (on) successful = startService();
        else successful = stopService();

        if (successful) updateUiStatus(on);
    }

    private void updateUiStatus(boolean on) {
        if (screen != null) screen.lockPreferences(on);

        if (toggleServer != null) {
            toggleServer.setTag(on);
            if (on) toggleServer.setImageResource(R.drawable.baseline_stop_24);
            else toggleServer.setImageResource(R.drawable.baseline_play_arrow_24);
        }

        if ((screen == null || toggleServer == null) && aria2 != null)
            runOnUiThread(aria2::askForStatus);
    }

    private boolean startService() {
        Prefs.putLong(PK.CURRENT_SESSION_START, System.currentTimeMillis());
        AnalyticsApplication.sendAnalytics(Utils.ACTION_TURN_ON);

        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            AskPermission.ask(this, Manifest.permission.WRITE_EXTERNAL_STORAGE, new AskPermission.Listener() {
                @Override
                public void permissionGranted(@NonNull String permission) {
                    toggleService(true);
                }

                @Override
                public void permissionDenied(@NonNull String permission) {
                    Toaster.with(MainActivity.this).message(R.string.writePermissionDenied).error(true).show();
                }

                @Override
                public void askRationale(@NonNull AlertDialog.Builder builder) {
                    builder.setTitle(R.string.permissionRequest)
                            .setMessage(R.string.writeStorageMessage);
                }
            });
            return false;
        }

        File sessionFile = new File(getFilesDir(), "session");
        if (Prefs.getBoolean(PK.SAVE_SESSION) && !sessionFile.exists()) {
            try {
                if (!sessionFile.createNewFile()) {
                    Toaster.with(this).message(R.string.failedCreatingSessionFile).error(true).show();
                    return false;
                }
            } catch (IOException ex) {
                Toaster.with(this).message(R.string.failedCreatingSessionFile).ex(ex).show();
                return false;
            }
        }

        aria2.startService();
        return true;
    }

    private boolean stopService() {
        aria2.stopService();

        Bundle bundle = null;
        if (Prefs.getLong(PK.CURRENT_SESSION_START, -1) != -1) {
            bundle = new Bundle();
            bundle.putLong(Utils.LABEL_SESSION_DURATION, System.currentTimeMillis() - Prefs.getLong(PK.CURRENT_SESSION_START, -1));
            Prefs.putLong(PK.CURRENT_SESSION_START, -1);
        }

        AnalyticsApplication.sendAnalytics(Utils.ACTION_TURN_OFF, bundle);
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    private void startDownloadBin(boolean importBin) {
        Bundle bundle = new Bundle();
        bundle.putBoolean("importBin", importBin);

        DownloadBinActivity.startActivity(this,
                getString(com.gianlu.aria2lib.R.string.downloadBin) + " - " + getString(com.gianlu.aria2lib.R.string.app_name),
                MainActivity.class, Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK, bundle);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.mainMenu_preferences:
                startActivity(new Intent(this, PreferenceActivity.class));
                return true;
            case R.id.mainMenu_changeBin:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.changeBinVersion)
                        .setMessage(R.string.changeBinVersion_message)
                        .setPositiveButton(android.R.string.yes, (dialogInterface, i) -> {
                            if (aria2.delete()) {
                                startDownloadBin(false);
                                finish();
                            } else {
                                Toaster.with(this).message(R.string.cannotDeleteBin).error(true).show();
                            }
                        }).setNegativeButton(android.R.string.no, null);

                showDialog(builder);
                return true;
            case R.id.mainMenu_customOptions:
                startActivity(new Intent(this, ConfigEditorActivity.class));
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    private void addLog(@NonNull Logging.LogLine line) {
        Logging.log(line);

        if (screen != null)
            screen.appendLogLine(line);
    }

    @Override
    public void onUpdateLogs(@NonNull List<Aria2Ui.LogMessage> list) {
        for (Aria2Ui.LogMessage msg : list) {
            Logging.LogLine line = createLogLine(msg);
            if (line != null) addLog(line);
        }
    }

    @Nullable
    private Logging.LogLine createLogLine(@NonNull Aria2Ui.LogMessage msg) {
        switch (msg.type) {
            case PROCESS_TERMINATED:
                return new Logging.LogLine(Logging.LogLine.Type.INFO, getString(R.string.logTerminated, msg.i));
            case PROCESS_STARTED:
                return new Logging.LogLine(Logging.LogLine.Type.INFO, getString(R.string.logStarted, msg.o));
            case MONITOR_FAILED:
                return null;
            case MONITOR_UPDATE:
                return null;
            case PROCESS_WARN:
                if (msg.o != null)
                    return new Logging.LogLine(Logging.LogLine.Type.WARNING, (String) msg.o);
            case PROCESS_ERROR:
                if (msg.o != null)
                    return new Logging.LogLine(Logging.LogLine.Type.ERROR, (String) msg.o);
            case PROCESS_INFO:
                if (msg.o != null)
                    return new Logging.LogLine(Logging.LogLine.Type.INFO, (String) msg.o);
        }

        return null;
    }

    @Override
    public void onMessage(@NonNull Aria2Ui.LogMessage msg) {
        if (msg.type == Message.Type.MONITOR_FAILED) {
            Logging.log("Monitor failed!", (Throwable) msg.o);
            return;
        }

        if (msg.type == Message.Type.MONITOR_UPDATE) return;

        Logging.LogLine line = createLogLine(msg);
        if (line != null) addLog(line);
    }

    @Override
    public void updateUi(boolean on) {
        updateUiStatus(on);
    }
}
