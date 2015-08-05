package com.ndn.bmmurphy.ndndl;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
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
    private long minTime_;
    private long maxTime_;
    private double average_ = 0, variance_ = 0, stdDev_ = 0;
    private double batteryStart_, batteryUsed_;
    private Context context_;
    private CpuMonitorThread cpu_;
    private String topTest_;

    public Statistics(Context context){
        minTime_ = Long.MAX_VALUE;
        maxTime_ = Long.MIN_VALUE;
        nTimeouts_ = 0;
        responseTimes_ = new ArrayList<>();
        context_ = context;
    }

    public void addItem(Long ii){
        responseTimes_.add(ii);
        if(ii < minTime_){
            minTime_ = ii;
        }
        if(ii > maxTime_){
            maxTime_ = ii;
        }
    }

    public void calculateStatistics(){
        calcVariance();
        calcMean();
    }

    public void addTimeout(){
        nTimeouts_++;
    }

    public void beginResourceMonitor(){
        cpu_ = new CpuMonitorThread();
        cpu_.start();
        startBatteryMonitor(context_);
    }

    public void stopResourceMonitor(){
        cpu_.interrupt();
        stopBatteryMonitor(context_);
    }

    private void startBatteryMonitor(Context context){
        IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, iFilter);
        int bStart = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        batteryStart_ = bStart;
    }

    private void stopBatteryMonitor(Context context){
        IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, iFilter);
        int batteryStop = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        batteryUsed_ = batteryStart_ - batteryStop;

    }

    private class CpuMonitorThread extends Thread{
        public void run(){
            BufferedReader buf;
            File logfile;
            BufferedWriter bufferedWriter;
            try{
                logfile = new File(context_.getExternalFilesDir(null), "topdump.log");
                if(!logfile.exists()){
                    logfile.createNewFile();
                }
                bufferedWriter = new BufferedWriter(new FileWriter(logfile, true));
            }
            catch(Exception e){
                return;
            }
            Process process = null;
            while(true) {
                try {
                    process = Runtime.getRuntime().exec("/system/bin/top -m 7 -n 1");
                    String tmp;
                    buf = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    StringBuilder log = new StringBuilder();
                    while ((tmp = buf.readLine())!= null) {
                        log.append(tmp);
                        log.append("\n");
                    }
                    buf.close();
                    process.destroy();
                    topTest_ = log.toString();

                    bufferedWriter.append(topTest_);
                    bufferedWriter.newLine();
                    Thread.sleep(430);
                } catch (Exception e) {
                    try {
                        bufferedWriter.close();
                        process.destroy();
                    }
                    catch(Exception f){ }
                    break;
                }
            }
        }
    }

    public String dumpStats(){
        calculateStatistics();
        DecimalFormat df = new DecimalFormat("#.##");
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(responseTimes_.size()/128.0) + "\t" +
                df.format(nTimeouts_) + "\t" +
                df.format(average_) + "\t" +
                df.format(stdDev_) + "\t" +
                df.format(minTime_) + "\t" +
                df.format(maxTime_) + "\t" +
                df.format(batteryUsed_);
    }

    public String toString(){
        return "";
    }

    private void calcVariance(){
        double var = 0;
        calcMean();
        for(long ll: responseTimes_){
            var += Math.pow((average_ - ll), 2);
        }
        variance_ = var/ responseTimes_.size();
        stdDev_ = Math.sqrt(variance_);
    }

    private void calcMean(){
        long total = 0;
        for(long ll: responseTimes_){
            total += ll;
        }
        average_ = total / (double) responseTimes_.size();
    }
}
