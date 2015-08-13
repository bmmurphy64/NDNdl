package com.ndn.bmmurphy.ndndl;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;
import net.named_data.jndn.OnData;
import net.named_data.jndn.OnTimeout;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;


public class MainActivity extends ActionBarActivity {

    private final String TAG = "NDNdl";

    private Spinner _sizeSelect;
    private Spinner _locationSelect;
    private PowerManager _mgr;
    private PowerManager.WakeLock wakelock;
    private ProgressDialog _proDlg;
    private EditText _nIterations;
    private EditText _timeOut;
    private Button _testButton;

    private final HashMap<String, String> _prefixes = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        _sizeSelect = (Spinner) findViewById(R.id.sizeSelect);
        _locationSelect = (Spinner) findViewById(R.id.locations);
        _nIterations = (EditText) findViewById(R.id.numIts);
        _timeOut = (EditText) findViewById(R.id.timeout);
        _testButton = (Button) findViewById(R.id.beginTest);
        _testButton.setOnClickListener(btnClickListener);
        _prefixes.put("Memphis", "/ndn/edu/memphis/download/");
        _prefixes.put("St. Louis", "/ndn/edu/wustl/memphis/download/");
        _prefixes.put("Switzerland", "/ndn/ch/unibas/memphis/download/");
        _prefixes.put("Korea", "/ndn/kr/anyang/memphis/download/");
        _prefixes.put("Los Angeles", "/ndn/org/caida/download/");
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
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    View.OnClickListener btnClickListener = new View.OnClickListener() {

        @Override
        public void onClick(View v) {

            // TODO Auto-generated method stub
            DownloadThread thread;

            //Determine whether we're doing a KB or MB download
            int dlSize = _sizeSelect.getSelectedItem().toString().equals("KB") ?
                        Integer.parseInt(_nIterations.getText().toString()) :
                        Integer.parseInt(_nIterations.getText().toString()) * 1024;
            /*
                Total number of segments to download is total size/8
                as traffic generator's max size is 8KB
             */
            dlSize = dlSize/8;
            int timeOut = Integer.parseInt(_timeOut.getText().toString());

            String downloadPrefix = _prefixes.get(_locationSelect.getSelectedItem().toString());

            _proDlg = ProgressDialog.show(MainActivity.this, "", "waiting...");

            //Allow the app to continue downloading even when the screen is turned off
            _mgr = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
            wakelock = _mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyWakeLock");
            wakelock.acquire();

            thread = new DownloadThread(dlSize, timeOut, downloadPrefix);

            thread.start();
        }
    };

    private class PingTimer implements OnData, OnTimeout
    {
        private long startTime;
        private long elapsedTime = 0;
        public int callbackCount_ = 0;

        public long getElapsedTime()
        {
            return elapsedTime;
        }

        public void onData(Interest interest, Data data)
        {
            ++callbackCount_;

            Log.i(TAG, "Got data packet with name " + data.getName().toUri());
            elapsedTime = System.currentTimeMillis() - this.startTime;

            String name = data.getName().toUri();
            String pingTarget = name.substring(0, name.lastIndexOf("/"));
            String contentStr = pingTarget + ": " + String.valueOf(elapsedTime) + " ms";
            Log.i(TAG, "Content " + contentStr);

        }

        public void onTimeout(Interest interest)
        {
            ++callbackCount_;
            Log.i(TAG, "Time out for interest " + interest.getName().toUri());
        }

        public void startUp()
        {
            startTime = System.currentTimeMillis();
        }
    }

    private class DownloadThread extends Thread {
        private int _nIterations;
        private int _timeOut;
        private String _downloadPrefix;
        private Statistics _statistics;

        public DownloadThread(int iterations, int timeout, String dlPrefix)
        {
            _nIterations = (iterations > 0) ? iterations : 5;
            _timeOut = (timeout > 0) ? timeout : 1000;
            _downloadPrefix = dlPrefix;
            _statistics = new Statistics(MainActivity.this.getApplicationContext());
        }

        @Override
        public void run() {
            Face face = new Face();
            _statistics.beginResourceMonitor();
            long testStart = System.currentTimeMillis();

            try {

                for(int ii = 0; ii < _nIterations; ii++) {

                    PingTimer timer = new PingTimer();

                    Name name = new Name(_downloadPrefix + "8kB/" + String.valueOf(ii));
                    Interest interest = new Interest(name, _timeOut);
                    interest.setMustBeFresh(true);
                    timer.startUp();
                    face.expressInterest(interest, timer, timer);

                    while(timer.callbackCount_ < 1){
                        face.processEvents();
                        Thread.sleep(5);
                    }

                    if(timer.getElapsedTime() != 0) {
                        _statistics.addItem(timer.getElapsedTime());
                    }

                    else{   //make note that it's timed out, but otherwise continue
                        _statistics.addTimeout();
                    }
                }

                long testEnd = System.currentTimeMillis() - testStart;
                _statistics.stopResourceMonitor();

                File logfile = new File(getExternalFilesDir(null), "results.log");
                BufferedWriter buf = null;

                if(!logfile.exists()){
                    logfile.createNewFile();
                    buf = new BufferedWriter(new FileWriter(logfile, true));
                    buf.append("Total Time (s)\tSize (MB)\tNum Timeouts\tAverage time\tStd Dev\tMin Time\tMax Time\tBattery % Usage");
                    buf.newLine();
                    buf.close();
                }

                buf = new BufferedWriter(new FileWriter(logfile, true));
                buf.append(String.valueOf(testEnd / 1000) + "\t" + _statistics.dumpStats());
                buf.newLine();
                buf.close();
            }

            catch(Exception e){
                Log.i(TAG, "Error:" + e.getMessage());
            }

            wakelock.release();
            _proDlg.dismiss();
        }

    }
}