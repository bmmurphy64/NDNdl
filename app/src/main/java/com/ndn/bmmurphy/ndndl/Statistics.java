package com.ndn.bmmurphy.ndndl;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;

/**
 * Created by Ben Murphy on 6/10/15.
 */
public class Statistics {
	
  private static final String TAG = "NDNdl";
  private ArrayList<Long> responseTimes_;
  private int nTimeouts_;
  private int timeoutMs_;
  private long totalTime_;
  private long minTime_;
  private long maxTime_;
  private double average_ = 0, variance_ = 0, stdDev_ = 0;
  private double batteryStart_, batteryUsed_;
  private Context context_;
  private CpuMonitorThread cpu_;
  private String topTest_;
  private String location_;
  private double cpuUsage_ = 0;

  public Statistics(Context context, int timeout, String loc) {
    minTime_ = Long.MAX_VALUE;
    maxTime_ = Long.MIN_VALUE;
    nTimeouts_ = 0;
    responseTimes_ = new ArrayList<>();
    context_ = context;
    timeoutMs_ = timeout;
    location_ = loc;
  }

  public void setTotalTime(long time) {
    totalTime_ = time;
  }
  public int getSuccess() {
    return responseTimes_.size();
  }
  public int getTimeouts() {
    return nTimeouts_;
  }

  public boolean isCpuMonitorAlive() {
    return cpu_.isAlive();
  }

  public void addItem(Long ii) {
    responseTimes_.add(ii);
    if (ii < minTime_) {
      minTime_ = ii;
    }
    if (ii > maxTime_) {
      maxTime_ = ii;
    }
  }

  public void addTimeout() {
    nTimeouts_++;
  }

  public void calculateStatistics() {
    calcVariance();
    calcMean();
  }

  public void beginResourceMonitor() {
    cpu_ = new CpuMonitorThread();
    cpu_.start();
    startBatteryMonitor(context_);
  }

  public void stopResourceMonitor() {
    cpu_.interrupt();
    stopBatteryMonitor(context_);
  }

  public void writeLogFile() {
    File logfile = new File(Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOCUMENTS), "NDNdl_results.log");
    Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOCUMENTS).mkdirs();
    BufferedWriter buf = null;
    try {
      if (!logfile.exists()) {
        logfile.createNewFile();
        buf = new BufferedWriter(new FileWriter(logfile, true));
        String header = "Location\t" +
            "Total Time (s)\t" +
            "Interest Timeout (ms)\t" +
            "Size (MB)\t" +
            "Throughput (MB/s)\t" +
            "Seg Timeouts\t" +
            "Timeout Percentage\t" +
            "Average Seg Time (ms)\t" +
            "Std Dev\t" +
            "Min Time\t" +
            "Max Time\t" +
            "Battery % Usage\t" +
            "Avg NFD CPU % Usage";
        buf.append(header);
        buf.newLine();
        buf.close();
      }

      buf = new BufferedWriter(new FileWriter(logfile, true));
      buf.append(dumpStats());
      buf.newLine();
      buf.close();
    }
    catch (Exception e) {
      Log.i(TAG, "Error: " + e.getMessage());
    }
  }
  private String dumpStats() {
    calculateStatistics();
    calculateCpuUsage();
    DecimalFormat df = new DecimalFormat("#.##");
    df.setRoundingMode(RoundingMode.HALF_UP);

    double dlSize = (responseTimes_.size() + nTimeouts_) / 128.0;

    String toReturn = location_ + "\t" +
        df.format(totalTime_ / 1000) + "\t" +
        String.valueOf(timeoutMs_) + "\t" +
        df.format(dlSize) + "\t" +
        df.format(dlSize / (totalTime_/1000)) + "\t" +
        df.format(nTimeouts_) + "\t" +
        df.format(100 * (double)nTimeouts_ / (
            nTimeouts_ + responseTimes_.size())) + "\t" +
        df.format(average_) + "\t" +
        df.format(stdDev_) + "\t" +
        df.format(minTime_) + "\t" +
        df.format(maxTime_) + "\t" +
        df.format(batteryUsed_) + "\t" +
        df.format(cpuUsage_);

    return toReturn;
  }

  private void startBatteryMonitor(Context context) {
    IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    Intent batteryStatus = context.registerReceiver(null, iFilter);
    int bStart = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    batteryStart_ = bStart;
  }

  private void stopBatteryMonitor(Context context) {
    IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    Intent batteryStatus = context.registerReceiver(null, iFilter);
    int batteryStop = batteryStatus.getIntExtra(
        BatteryManager.EXTRA_LEVEL, -1);
    batteryUsed_ = batteryStart_ - batteryStop;

  }

  private class CpuMonitorThread extends Thread {
    public void run() {
      BufferedReader buf;
      File logfile;
      BufferedWriter bufferedWriter = null;
      try {
        logfile = new File(context_.getExternalFilesDir(null),
            "topdump.log");
        if (!logfile.exists()) {
          logfile.createNewFile();
        }
        bufferedWriter = new BufferedWriter(new FileWriter(logfile,
            false));
      }
      catch (Exception e) {
        Log.i(TAG, "Error: " + e.getMessage());
      }
      Process process = null;
      while (true) {
        try {
          process = Runtime.getRuntime().exec(
              "/system/bin/top -m 7 -n 1");
          String tmp;
          buf = new BufferedReader(new InputStreamReader(
              process.getInputStream()));
          StringBuilder log = new StringBuilder();
          while ((tmp = buf.readLine())!= null) {
            tmp += " ";
            log.append(tmp);
            log.append("\n");
          }
          buf.close();
          process.destroy();
          topTest_ = log.toString();

          bufferedWriter.append(topTest_);
          //bufferedWriter.newLine();
          Thread.sleep(430);
        }
        catch (Exception e) {
          try {
            bufferedWriter.close();
            process.destroy();
          }
          catch (Exception f){ }
          break;
        }
      }
    }
  }

  private void calculateCpuUsage() {
    try{
      BufferedReader br = null;
      br = new BufferedReader(new InputStreamReader(new FileInputStream(
          new File(context_.getExternalFilesDir(null),
              "topdump.log"))));
      File file = new File(context_.getExternalFilesDir(null),
          "topdump.log");
      int nSamples = 0;
      int cpuTotal = 0;
      String line;
      while ((line = br.readLine()) != null) {
        if (line.matches("(.*)PID(.*)")) {
          nSamples++;
        }
        if (line.matches("(.*)NfdService(.*)")) {;
          String[] tokens = line.split("\\s+");
          cpuTotal += Integer.parseInt(tokens[2].substring(
              0, tokens[2].length() - 1));
          cpuUsage_ = cpuTotal / (double)nSamples;
        }

      }
    }
    catch (Exception e) {
      Log.i(TAG, "Error: " + e.getMessage());
    }
  }

  private void calcVariance() {
    double var = 0;
    calcMean();
    for (long ll: responseTimes_) {
      var += Math.pow((average_ - ll), 2);
    }
    variance_ = var / responseTimes_.size();
    stdDev_ = Math.sqrt(variance_);
  }

  private void calcMean() {
    long total = 0;
    for (long ll: responseTimes_) {
      total += ll;
    }
    average_ = total / (double) responseTimes_.size();
  }
}
