package com.pat.obd_scanner.activity;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.SensorManager;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.github.pires.obd.commands.ObdCommand;
import com.github.pires.obd.commands.SpeedCommand;
import com.github.pires.obd.commands.engine.RPMCommand;
import com.github.pires.obd.commands.engine.RuntimeCommand;
import com.github.pires.obd.enums.AvailableCommandNames;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.pat.obd_scanner.R;
import com.pat.obd_scanner.config.ObdConfig;
import com.pat.obd_scanner.io.AbstractGatewayService;
import com.pat.obd_scanner.io.LogCSVWriter;
import com.pat.obd_scanner.io.MockObdGatewayService;
import com.pat.obd_scanner.io.ObdCommandJob;
import com.pat.obd_scanner.io.ObdGatewayService;
import com.pat.obd_scanner.io.ObdProgressListener;
import com.pat.obd_scanner.net.ObdReading;
import com.pat.obd_scanner.net.ObdService;
import com.pat.obd_scanner.trips.TripLog;
import com.pat.obd_scanner.trips.TripRecord;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import butterknife.BindView;
import butterknife.ButterKnife;
import retrofit2.Response;
import retrofit2.Retrofit;

import static com.pat.obd_scanner.activity.ConfigActivity.getGpsDistanceUpdatePeriod;
import static com.pat.obd_scanner.activity.ConfigActivity.getGpsUpdatePeriod;

public class MainActivity extends AppCompatActivity implements ObdProgressListener, LocationListener, GpsStatus.Listener {

    private static final String TAG = MainActivity.class.getName();
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int NO_BLUETOOTH_ID = 0;
    private static final int BLUETOOTH_DISABLED = 1;
    private static final int START_LIVE_DATA = 2;
    private static final int STOP_LIVE_DATA = 3;
    private static final int SETTINGS = 4;
    private static final int GET_DTC = 5;
    private static final int TABLE_ROW_MARGIN = 7;
    private static final int NO_ORIENTATION_SENSOR = 8;
    private static final int NO_GPS_SUPPORT = 9;
    private static final int TRIPS_LIST = 10;
    private static final int SAVE_TRIP_NOT_AVAILABLE = 11;
    private static final int REQUEST_ENABLE_BT = 1234;
    @BindView(R.id.data_table)
    TableLayout tableLayout;
    @BindView(R.id.view)
    LinearLayout linearLayout;
    @BindView(R.id.BT_STATUS)
    TextView btStatusTextView;
    @BindView(R.id.GPS_POS)
    TextView gpsStatusTextView;
    @BindView(R.id.OBD_STATUS)
    TextView obdStatusTextView;
    @BindView(R.id.fab)
    FloatingActionButton fab;
    private boolean bluetoothDefaultIsEnable = false;
    private boolean running = false;
    @Inject
    SensorManager sensorManager;
    @Inject
    public
    SharedPreferences prefs;

    public Map<String, String> commandResult = new HashMap<String, String>();

    String[] PERMISSIONS = {
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
    };

    private LogCSVWriter myCSVWriter;
    private boolean mGpsIsStarted = false;
    private Location mLastLocation;
    private LocationManager locationManager;
    private boolean preReqs = true;
    private boolean isServiceBound;
    private AbstractGatewayService service;
    private ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder iBinder) {
            Log.d(TAG, name.toString() + " service is bound");
            isServiceBound = true;
            service = ((AbstractGatewayService.AbstractGatewayServiceBinder) iBinder).getService();
            service.setContext(MainActivity.this);
            Log.d(TAG, "Starting live data");
            try {
                service.startService();
                if (preReqs)
                    btStatusTextView.setText(getString(R.string.status_bluetooth_connected));
            } catch (IOException ioe) {
                Log.e(TAG, "Failure Starting live data");
                btStatusTextView.setText(getString(R.string.status_bluetooth_error_connecting));
                doUnbindService();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, name.toString() + " service is unbound");
            isServiceBound = false;
        }
    };

    private TripRecord currentTrip;
    public TripLog triplog;
    private final Runnable mQueueCommands = new Runnable() {
        @Override
        public void run() {
            if (service != null && service.isRunning() && service.queueEmpty()) {
                queueCommands();

                double lat = 0;
                double lon = 0;
                double alt = 0;
                final int posLen = 7;
                if (mGpsIsStarted && mLastLocation != null) {
                    lat = mLastLocation.getLatitude();
                    lon = mLastLocation.getLongitude();
                    alt = mLastLocation.getAltitude();

                    StringBuilder sb = new StringBuilder();
                    sb.append("Lat: ");
                    sb.append(String.valueOf(mLastLocation.getLatitude()).substring(0, posLen));
                    sb.append(" Lon: ");
                    sb.append(String.valueOf(mLastLocation.getLongitude()).substring(0, posLen));
                    sb.append(" Alt: ");
                    sb.append(String.valueOf(mLastLocation.getAltitude()));
                    gpsStatusTextView.setText(sb.toString());
                }
                if (prefs.getBoolean(ConfigActivity.UPLOAD_DATA_KEY, false)) {
                    // Upload the current reading by http
                    final String vin = prefs.getString(ConfigActivity.VEHICLE_ID_KEY, "UNDEFINED_VIN");
                    Map<String, String> temp = new HashMap<String, String>();
                    temp.putAll(commandResult);
                    ObdReading reading = new ObdReading(lat, lon, alt, System.currentTimeMillis(), vin, temp);
                    new UploadAsyncTask().execute(reading);

                } else if (prefs.getBoolean(ConfigActivity.ENABLE_FULL_LOGGING_KEY, false)) {
                    // Write the current reading to CSV
                    final String vin = prefs.getString(ConfigActivity.VEHICLE_ID_KEY, "UNDEFINED_VIN");
                    Map<String, String> temp = new HashMap<String, String>();
                    temp.putAll(commandResult);
                    ObdReading reading = new ObdReading(lat, lon, alt, System.currentTimeMillis(), vin, temp);
                    if (reading != null) myCSVWriter.writeLineCSV(reading);
                }
                commandResult.clear();
            }
            // run again in period defined in preferences
            new Handler().postDelayed(mQueueCommands, ConfigActivity.getObdUpdatePeriod(prefs));
        }
    };
    private LocationProvider locationProvider;

    private void addTableRow(String id, String key, String val) {

        TableRow tr = new TableRow(this);
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(TABLE_ROW_MARGIN, TABLE_ROW_MARGIN, TABLE_ROW_MARGIN,
                TABLE_ROW_MARGIN);
        tr.setLayoutParams(params);

        TextView name = new TextView(this);
        name.setGravity(Gravity.RIGHT);
        name.setText(key + ": ");
        TextView value = new TextView(this);
        value.setGravity(Gravity.LEFT);
        value.setText(val);
        value.setTag(id);
        tr.addView(name);
        tr.addView(value);
        tableLayout.addView(tr, params);
    }

    private void queueCommands() {
        if (isServiceBound) {
            for (ObdCommand Command : ObdConfig.getCommands()) {
                if (prefs.getBoolean(Command.getName(), true))
                    service.queueJob(new ObdCommandJob(Command));
            }
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 1: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                }
                return;
            }
            // other 'case' lines to check for other
            // permissions this app might request
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        ActivityCompat.requestPermissions(this, PERMISSIONS, 1);


        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (running) {
                    stopLiveData();
                } else {
                    startLiveData();
                }
            }
        });
        ButterKnife.bind(this);

        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null)
            bluetoothDefaultIsEnable = btAdapter.isEnabled();
        triplog = TripLog.getInstance(this.getApplicationContext());
        fab.setImageResource(R.drawable.icons8_play);

        obdStatusTextView.setText("Disconnected");
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "Entered onStart...");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Resuming");

        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();

        preReqs = btAdapter != null && btAdapter.isEnabled();

        if (!preReqs && prefs.getBoolean(ConfigActivity.ENABLE_BT_KEY, false)) {
            preReqs = btAdapter != null && btAdapter.enable();
        }

        gpsInit();

        if (!preReqs) {
            btStatusTextView.setText(getString(R.string.status_bluetooth_disabled));
        } else {
            btStatusTextView.setText(getString(R.string.status_bluetooth_ok));
        }

    }

    private boolean gpsInit() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gpsStatusTextView.setText("ready");
            return true;
        }
        gpsStatusTextView.setText("not ready");
        Log.d(TAG, "GPS not connected");
        return false;
    }

    private void stopLiveData() {
        running = false;
        Log.d(TAG, "Stopping");
        Log.d(TAG, "Stopping live data..");
        fab.setImageResource(R.drawable.icons8_play);

        gpsStop();

        doUnbindService();
        endTrip();

        final String devemail = prefs.getString(ConfigActivity.DEV_EMAIL_KEY, null);
        if (devemail != null && !devemail.isEmpty()) {
            DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    switch (which) {
                        case DialogInterface.BUTTON_POSITIVE:
                            ObdGatewayService.saveLogcatToFile(getApplicationContext(), devemail);
                            break;

                        case DialogInterface.BUTTON_NEGATIVE:
                            //No button clicked
                            break;
                    }
                }
            };
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage("Where there issues?\nThen please send us the logs.\nSend Logs?").setPositiveButton("Yes", dialogClickListener)
                    .setNegativeButton("No", dialogClickListener).show();
        }

        if (myCSVWriter != null) {
            myCSVWriter.closeLogCSVWriter();
        }
    }

    private void endTrip() {
        if (currentTrip != null) {
            currentTrip.setEndDate(new Date());
            triplog.updateRecord(currentTrip);
        }
    }

    private void gpsStop() {
        if (mGpsIsStarted) {
            locationManager.removeUpdates((LocationListener) this);
            mGpsIsStarted = false;
            gpsStatusTextView.setText(getString(R.string.status_gps_stopped));
        }
    }

    private void startLiveData() {
        running = true;
        Log.d(TAG, "Running");
        tableLayout.removeAllViews();
        doBindService();
        fab.setImageResource(R.drawable.icons8_stop);
        currentTrip = triplog.startTrip();
        if (currentTrip == null)
            Toast.makeText(this, "Save Trip not available", Toast.LENGTH_LONG);

        // start command execution
        new Handler().post(mQueueCommands);

        if (prefs.getBoolean(ConfigActivity.ENABLE_GPS_KEY, false))
            gpsStart();
        else
            gpsStatusTextView.setText(getString(R.string.status_gps_not_used));


        if (prefs.getBoolean(ConfigActivity.ENABLE_FULL_LOGGING_KEY, false)) {

            // Create the CSV Logger
            long mils = System.currentTimeMillis();
            SimpleDateFormat sdf = new SimpleDateFormat("_dd_MM_yyyy_HH_mm_ss");

            try {
                myCSVWriter = new LogCSVWriter("Log" + sdf.format(new Date(mils)).toString() + ".csv",
                        prefs.getString(ConfigActivity.DIRECTORY_FULL_LOGGING_KEY,
                                getString(R.string.default_dirname_full_logging))
                );
            } catch (FileNotFoundException | RuntimeException e) {
                Log.e(TAG, "Can't enable logging to file.", e);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                btStatusTextView.setText(getString(R.string.status_bluetooth_connected));
            } else {
                Toast.makeText(this, R.string.text_bluetooth_disabled, Toast.LENGTH_LONG).show();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void updateTripStatistic(final ObdCommandJob job, final String cmdID) {

        if (currentTrip != null) {
            if (cmdID.equals(AvailableCommandNames.SPEED.toString())) {
                SpeedCommand command = (SpeedCommand) job.getCommand();
                currentTrip.setSpeedMax(command.getMetricSpeed());
            } else if (cmdID.equals(AvailableCommandNames.ENGINE_RPM.toString())) {
                RPMCommand command = (RPMCommand) job.getCommand();
                currentTrip.setEngineRpmMax(command.getRPM());
            } else if (cmdID.endsWith(AvailableCommandNames.ENGINE_RUNTIME.toString())) {
                RuntimeCommand command = (RuntimeCommand) job.getCommand();
                currentTrip.setEngineRuntime(command.getFormattedResult());
            }
        }
    }

    private synchronized void gpsStart() {
        if (!mGpsIsStarted && locationProvider != null && locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    Activity#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for Activity#requestPermissions for more details.
                    return;
                }
            }
            locationManager.requestLocationUpdates(locationProvider.getName(), getGpsUpdatePeriod(prefs), getGpsDistanceUpdatePeriod(prefs), (LocationListener) this);
            mGpsIsStarted = true;
        } else {
            gpsStatusTextView.setText(getString(R.string.status_gps_no_support));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (locationManager != null) {
            locationManager.removeGpsStatusListener((GpsStatus.Listener) this);
            locationManager.removeUpdates((LocationListener) this);
        }

        //releaseWakeLockIfHeld();
        if (isServiceBound) {
            doUnbindService();
        }

        endTrip();

        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null && btAdapter.isEnabled() && !bluetoothDefaultIsEnable)
            btAdapter.disable();
    }

    private void getTroubleCodes() {
        startActivity(new Intent(this, TroubleCodesActivity.class));
    }

    private void doBindService() {
        if (!isServiceBound) {
            Log.d(TAG, "Binding OBD service..");
            if (preReqs) {
                btStatusTextView.setText(getString(R.string.status_bluetooth_connecting));
                Intent serviceIntent = new Intent(this, ObdGatewayService.class);
                bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
            } else {
                btStatusTextView.setText(getString(R.string.status_bluetooth_disabled));
                Intent serviceIntent = new Intent(this, MockObdGatewayService.class);
                bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
            }
        }
    }

    private void doUnbindService() {
        if (isServiceBound) {
            if (service.isRunning()) {
                service.stopService();
                if (preReqs)
                    btStatusTextView.setText(getString(R.string.status_bluetooth_ok));
            }
            Log.d(TAG, "Unbinding OBD service..");
            unbindService(serviceConn);
            isServiceBound = false;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        switch (item.getItemId()) {
            case R.id.settings:
                updateConfig();
                return true;
            case R.id.troubleCodes:
                getTroubleCodes();
                return true;
            case R.id.triplList:
                startActivity(new Intent(this, TripListActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }

    }

    private void updateConfig() {
        startActivity(new Intent(this, ConfigActivity.class));
    }

    public void stateUpdate(final ObdCommandJob job) {
        final String cmdName = job.getCommand().getName();
        String cmdResult = "";
        final String cmdID = LookUpCommand(cmdName);

        if (job.getState().equals(ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR)) {
            cmdResult = job.getCommand().getResult();
            if (cmdResult != null && isServiceBound) {
                obdStatusTextView.setText(cmdResult.toLowerCase());
            }
        } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.BROKEN_PIPE)) {
            if (isServiceBound)
                stopLiveData();
        } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.NOT_SUPPORTED)) {
            cmdResult = getString(R.string.status_obd_no_support);
        } else {
            cmdResult = job.getCommand().getFormattedResult();
            if (isServiceBound)
                obdStatusTextView.setText(getString(R.string.status_obd_data));
        }

        if (linearLayout.findViewWithTag(cmdID) != null) {
            TextView existingTV = (TextView) linearLayout.findViewWithTag(cmdID);
            existingTV.setText(cmdResult);
        } else addTableRow(cmdID, cmdName, cmdResult);
        commandResult.put(cmdID, cmdResult);
        updateTripStatistic(job, cmdID);
    }


    public static String LookUpCommand(String txt) {
        for (AvailableCommandNames item : AvailableCommandNames.values()) {
            if (item.getValue().equals(txt)) return item.name();
        }
        return txt;
    }

    @Override
    public void onLocationChanged(Location location) {
        mLastLocation = location;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }

    @Override
    public void onGpsStatusChanged(int event) {
        switch (event) {
            case GpsStatus.GPS_EVENT_STARTED:
                gpsStatusTextView.setText(getString(R.string.status_gps_started));
                break;
            case GpsStatus.GPS_EVENT_STOPPED:
                gpsStatusTextView.setText(getString(R.string.status_gps_stopped));
                break;
            case GpsStatus.GPS_EVENT_FIRST_FIX:
                gpsStatusTextView.setText(getString(R.string.status_gps_fix));
                break;
            case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
                break;
        }
    }

    private class UploadAsyncTask extends AsyncTask<ObdReading, Void, Void> {

        @Override
        protected Void doInBackground(ObdReading... readings) {
            Log.d(TAG, "Uploading " + readings.length + " readings..");
            // instantiate reading service client
            final String endpoint = prefs.getString(ConfigActivity.UPLOAD_URL_KEY, "");
            Retrofit restAdapter = new Retrofit.Builder()
                    .baseUrl(endpoint)
                    .build();
            ObdService service = restAdapter.create(ObdService.class);
            // upload readings
            for (ObdReading reading : readings) {
                try {
                    Response response = service.uploadReading(reading);
                    assert response.isSuccessful();
                } catch (Error re) {
                    Log.e(TAG, re.toString());
                }

            }
            Log.d(TAG, "Done");
            return null;
        }

    }
}
