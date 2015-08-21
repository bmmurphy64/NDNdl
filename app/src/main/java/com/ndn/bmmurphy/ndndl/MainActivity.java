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
import android.widget.TextView;

import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;
import net.named_data.jndn.OnData;
import net.named_data.jndn.OnTimeout;

import java.util.HashMap;


public class MainActivity extends ActionBarActivity {

  private final String TAG = "NDNdl";
  private final String TIMEOUT_MSG = "Error: All Interests timed out. " +
      "Check your NFD configuration and make sure " +
      "the location you're downloading from is up.";
  private final String SUCCESS_MSG = "Test completed successfully." +
      "Results are stored in Documents/NDNdl_results.log";


  private Spinner locationSelect_;
  private PowerManager.WakeLock wakelock_;
  private ProgressDialog proDlg_;
  private EditText nIterations_;
  private EditText timeOut_;
  private TextView errorPanel_;

  private HashMap<String, String> prefixes_ = new HashMap<>();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    locationSelect_ = (Spinner) findViewById(R.id.locations);
    nIterations_ = (EditText) findViewById(R.id.numIts);
    timeOut_ = (EditText) findViewById(R.id.timeout);
    errorPanel_ = (TextView) findViewById(R.id.errorView);
    Button testStartButton_ = (Button) findViewById(R.id.beginTest);
    testStartButton_.setOnClickListener(btnClickListener);
    prefixes_.put("Memphis", "/ndn/edu/memphis/download/");
    prefixes_.put("St. Louis", "/ndn/edu/wustl/memphis/download/");
    prefixes_.put("Switzerland", "/ndn/ch/unibas/memphis/download/");
    prefixes_.put("Korea", "/ndn/kr/anyang/memphis/download/");
    prefixes_.put("Los Angeles", "/ndn/org/caida/download/");
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

      proDlg_ = ProgressDialog.show(MainActivity.this, "", "waiting...");

      PowerManager mgr_ = (PowerManager)
          getApplicationContext().getSystemService(
              Context.POWER_SERVICE);
      wakelock_ = mgr_.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
          "MyWakeLock");
      wakelock_.acquire();

      int dlSize = Integer.parseInt(nIterations_.getText().toString()) *
          1024;

      //Total number of segments to download is total size/8
      //as traffic generator's max size is 8KB

      dlSize = dlSize/8;
      int timeOut = Integer.parseInt(timeOut_.getText().toString());

      String downloadLocation =
          locationSelect_.getSelectedItem().toString();

      thread = new DownloadThread(dlSize, timeOut, downloadLocation);

      thread.start();
    }
  };

  private class PingTimer implements OnData, OnTimeout
  {
    private long startTime;
    private long elapsedTime = 0;
    private Statistics statistics;

    public PingTimer(Statistics stats) {
      statistics = stats;
    }

    public void onData(Interest interest, Data data) {
      elapsedTime = System.currentTimeMillis() - this.startTime;

      statistics.addItem(elapsedTime);

      String name = data.getName().toUri();
      String pingTarget = name.substring(0, name.lastIndexOf("/"));
      String contentStr = pingTarget + ": " +
              String.valueOf(elapsedTime) + " ms";
      Log.i(TAG, "Content " + contentStr);

    }

    public void onTimeout(Interest interest) {
      statistics.addTimeout();
      Log.i(TAG, "Time out for interest " + interest.getName().toUri());
    }

    public void startUp() {
      startTime = System.currentTimeMillis();
    }
  }

  private class DownloadThread extends Thread {
    private int nIterations_;
    private int timeOut_;
    private String downloadPrefix_;
    private Statistics statistics_;

    public DownloadThread(int iterations, int timeout, String dlLoc) {
	  if (iterations > 0) {
		nIterations_ = iterations;  
	  }
	  else {
		nIterations = 100;  
	  }
	  if (timeout > 0) {
		timeOut_ = timeout;  
	  }
	  else {
		  timeOut_ = 1000;
	  }
      downloadPrefix_ = prefixes_.get(dlLoc);
      statistics_ = new Statistics(
          MainActivity.this.getApplicationContext(),
          timeOut_, dlLoc
      );
    }

    @Override
    public void run() {
      runOnUiThread(new Runnable() {
        public void run() {
          errorPanel_.setText("");
        }
      });
      Face face = new Face();
      statistics_.beginResourceMonitor();
      long testStart = System.currentTimeMillis();

      try {
        for (int i = 0; i < nIterations_; i++) {
          PingTimer timer = new PingTimer(statistics_);

          Name name = new Name(downloadPrefix_ + "8kB/" +
                  String.valueOf(i));
          Interest interest = new Interest(name, timeOut_);
          interest.setMustBeFresh(true);
          timer.startUp();
          face.expressInterest(interest, timer, timer);
          Thread.sleep(10);
          //Process events every 20 Interests to ensure nothing times out.
          if (i % 20 == 0) {
            face.processEvents();
            Thread.sleep(10);
          }
        }

        while ((statistics_.getSuccess() + statistics_.getTimeouts()) <
            nIterations_) {
        face.processEvents();
        Thread.sleep(10);
        }

        long testEnd = System.currentTimeMillis() - testStart;
        statistics_.setTotalTime(testEnd);
        statistics_.stopResourceMonitor();
        while (statistics_.isCpuMonitorAlive()) {
          //no-op to ensure the top dump file is closed
        }
        if (statistics_.getSuccess() != 0) {
          statistics_.writeLogFile();
          runOnUiThread(
              new Runnable() {
                public void run() {
                  errorPanel_.setText(SUCCESS_MSG);
                }
              }
          );
        }
        else {
          runOnUiThread(
              new Runnable() {
                public void run() {
                  errorPanel_.setText(TIMEOUT_MSG);
            }
          });
        }
      }
      catch (Exception e) {
        Log.i(TAG, "Error:" + e.getMessage());
      }

      wakelock_.release();
      proDlg_.dismiss();
    }

  }
}
