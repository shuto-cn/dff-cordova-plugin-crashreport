/**
 * 
 */
package com.dff.cordova.plugin.crashreport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.apache.cordova.CordovaInterface;
import org.json.JSONException;
import org.json.JSONObject;

import com.dff.cordova.plugin.common.AbstractPluginListener;
import com.dff.cordova.plugin.common.log.CordovaPluginLog;
import com.dff.cordova.plugin.crashreport.json.model.JsonDebugMemoryInfo;
import com.dff.cordova.plugin.crashreport.json.model.JsonMemoryInfo;
import com.dff.cordova.plugin.crashreport.json.model.JsonProcessErrorStateInfo;
import com.dff.cordova.plugin.crashreport.json.model.JsonRunningAppProcessInfo;
import com.dff.cordova.plugin.crashreport.json.model.JsonRunningServiceInfo;
import com.dff.cordova.plugin.crashreport.json.model.JsonThread;
import com.dff.cordova.plugin.crashreport.json.model.JsonThrowable;
import com.dff.cordova.plugin.packagemanager.model.json.JSONPackageInfo;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Environment;
import android.util.Log;

/**
 * @author frank
 *
 */
public class CrashReporter extends AbstractPluginListener implements UncaughtExceptionHandler {
	public static final String LOG_TAG = "com.dff.cordova.plugin.crashreport.CrashReporter";
	
	private static final int PACKAGE_INFO_FLAGS = PackageManager.GET_ACTIVITIES
			| PackageManager.GET_CONFIGURATIONS
			| PackageManager.GET_GIDS
			| PackageManager.GET_INSTRUMENTATION
			| PackageManager.GET_INTENT_FILTERS
			| PackageManager.GET_META_DATA
			| PackageManager.GET_PERMISSIONS
			| PackageManager.GET_PROVIDERS
			| PackageManager.GET_RECEIVERS
			| PackageManager.GET_SERVICES
			| PackageManager.GET_SHARED_LIBRARY_FILES
			| PackageManager.GET_SIGNATURES
			| PackageManager.GET_URI_PERMISSION_PATTERNS;
	
	private static volatile boolean mCrashing = false;
	private UncaughtExceptionHandler defaultHandler;
	private CordovaInterface cordova;
	
	public CrashReporter(UncaughtExceptionHandler defaultHandler, CordovaInterface cordova) {
		this.defaultHandler = defaultHandler;
		this.cordova = cordova;
	}

	/* (non-Javadoc)
	 * @see java.lang.Thread.UncaughtExceptionHandler#uncaughtException(java.lang.Thread, java.lang.Throwable)
	 */
	@Override
	public void uncaughtException(Thread t, Throwable e) {
		// Don't re-enter -- avoid infinite loops if crash-reporting crashes.
        if (mCrashing) {
        	return;
        }
        mCrashing = true;
		
		try {
			CordovaPluginLog.e(LOG_TAG, e.getMessage(), e);			
			JSONObject jsonCrashReport = createCrashreport(t, e, this.cordova.getActivity());			
			super.sendPluginResult(jsonCrashReport);
		}
		catch (Throwable e1) {
			try {
				CordovaPluginLog.e(LOG_TAG, e1.getMessage(), e1);
			}
			catch (Throwable t3) {
				Log.e(LOG_TAG, t3.getMessage(), t3);
			}			
		}
		finally {
			// finally call initial exception handler
			defaultHandler.uncaughtException(t, e);
		}		
	}
	
	public static JSONObject createCrashreport(Thread t, Throwable e, Context context) {
		JSONObject jsonCrashReport = new JSONObject();
		String date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss.SSS").format(new Date());
		int pid = android.os.Process.myPid();
		ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		ActivityManager.RunningAppProcessInfo myMemoryOutState = new ActivityManager.RunningAppProcessInfo();
		ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
		
		ActivityManager.getMyMemoryState(myMemoryOutState);
		activityManager.getMemoryInfo(memoryInfo);
		android.os.Debug.MemoryInfo[] memoryInfos = activityManager.getProcessMemoryInfo(new int[] {pid});
		List<ActivityManager.RunningAppProcessInfo> runningAppProcessessInfo = activityManager.getRunningAppProcesses();
		List<ActivityManager.ProcessErrorStateInfo> processErrorStateInfo = activityManager.getProcessesInErrorState();
		List<ActivityManager.RunningServiceInfo> runningServiceInfo = activityManager.getRunningServices(1000);
		
		String packagename = context.getPackageName();
		PackageManager packageManager = context.getPackageManager();
		
		PackageInfo packageinfo = null;
		try {
			packageinfo = packageManager.getPackageInfo(packagename, PACKAGE_INFO_FLAGS);
		}
		catch (NameNotFoundException e1) {
			CordovaPluginLog.e(LOG_TAG, e1.getMessage(), e1);
		}
		
		try {
			if (packageinfo != null) {
				jsonCrashReport.put("packageInfo",JSONPackageInfo.toJSON(packageinfo));
			}
			
			jsonCrashReport.put("runningAppProcesses", JsonRunningAppProcessInfo.toJson(runningAppProcessessInfo));
			jsonCrashReport.put("processErrorStateInfo", JsonProcessErrorStateInfo.toJson(processErrorStateInfo));
			jsonCrashReport.put("runningServiceInfo", JsonRunningServiceInfo.toJson(runningServiceInfo));				
			jsonCrashReport.put("myMemoryState", JsonRunningAppProcessInfo.toJson(myMemoryOutState));
			jsonCrashReport.put("memoryInfo", JsonMemoryInfo.toJson(memoryInfo));
			jsonCrashReport.put("debugMemoryInfo", JsonDebugMemoryInfo.toJson(memoryInfos));
			jsonCrashReport.put("memoryClass", activityManager.getMemoryClass());
			jsonCrashReport.put("lowRamDevice", activityManager.isLowRamDevice());
			jsonCrashReport.put("isUserAMonkey", ActivityManager.isUserAMonkey());
			jsonCrashReport.put("isRunningInTestHarness", ActivityManager.isRunningInTestHarness());
					
			jsonCrashReport.put("pid", pid);
			jsonCrashReport.put("date", date);
			jsonCrashReport.put("thread", JsonThread.toJson(t));
			jsonCrashReport.put("throwable", JsonThrowable.toJson(e));
		}
		catch(JSONException je) {
			CordovaPluginLog.e(LOG_TAG, je.getMessage(), je);
		}
		
		if (isExternalStorageWritable()) {
			File crashReportDir = new File(context.getExternalFilesDir(null), "crashreports");
			
			if (!crashReportDir.mkdirs()) {
				CordovaPluginLog.w(LOG_TAG, crashReportDir.getAbsolutePath() + " not created");
			}
			
			if (crashReportDir.exists()) {
				String filename = "crashreport_" + date + ".txt";
				File crashReportFile = new File(crashReportDir, filename);
				
				try {
					if (!crashReportFile.exists() && crashReportFile.createNewFile()) {
						CordovaPluginLog.i(LOG_TAG, crashReportFile.getAbsolutePath() + " created");
					}
					
					FileOutputStream outputStream = new FileOutputStream(crashReportFile, true);
					
					outputStream.write(jsonCrashReport.toString().getBytes());
					outputStream.flush();
					outputStream.close();
				}
				catch (IOException ioe) {
					CordovaPluginLog.e(LOG_TAG, ioe.getMessage(), ioe);
				}
			}
			else {
				CordovaPluginLog.w(LOG_TAG, crashReportDir.getAbsolutePath() + " does not exist");
			}
		}
		
		return jsonCrashReport;
	}
	
	/* Checks if external storage is available for read and write */
	public static boolean isExternalStorageWritable() {		
		return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
	}

}
