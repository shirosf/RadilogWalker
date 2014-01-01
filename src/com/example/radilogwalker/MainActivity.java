package com.example.radilogwalker;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Environment;
import android.os.PowerManager;
import android.util.Log;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.Dialog;
import android.widget.Button;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.CheckBox;
import android.location.LocationManager;
import android.location.LocationListener;
import android.location.Location;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.driver.UsbId;
import java.util.Arrays;
import java.util.List;
import java.util.Date;
import java.io.IOException;
import java.io.File;
import java.io.FileWriter;
import java.lang.Exception;
import java.text.SimpleDateFormat;

public class MainActivity extends Activity
{
    private UsbManager mUsbManager;
    private TextView mStatusMessage;
    private TextView mDataValueMessage;
    private UsbDevice mDevice=null;
    private UsbSerialDriver mDriver=null;
    private int mRectime=5; // minutes
    private boolean mAutoRecord=false;
    private Context mContext;

    private static final int MESSAGE_REFRESH = 101;
    private static final int MESSAGE_READDATA = 102;
    private static final long REFRESH_TIMEOUT_MILLIS = 5000;
    private final String TAG = MainActivity.class.getSimpleName();
    private enum MeasurementTime { MES30S, MES10S, MES03S }
    private MeasurementTime mMesTime = MeasurementTime.MES30S;
    private Menu mMenu;
    private boolean mDeviceInitialized = false;
    private LocationListener mLocationListener;
    private LocationManager mLocationManager;
    private boolean mLocationListenerUpdated=false;
    private double mDoseRate;
    private double mCurrentLongitude;
    private double mCurrentLatitude;
    private Date mGpsCaptureDate;
    private Date mDoseRateCaptureDate;
    private Date mLastRecordDate;
    private String mDataFileName;
    private FileWriter mRecordFileWriter=null;
    private Window mWindow;

    // use 3sec read interval for all measurement time
    private int getReadInterval()
    {
	switch(mMesTime){
	case MES30S:
	    return 5000;
	case MES10S:
	    return 5000;
	case MES03S:
	    return 3000;
	}
	return 3000;
    }
	
    private byte[] getMestimeString()
    {
	switch(mMesTime){
	case MES30S:
	    return TCOM_MES30SEC;
	case MES10S:
	    return TCOM_MES10SEC;
	case MES03S:
	    return TCOM_MES03SEC;
	}
	return TCOM_MES30SEC;
    }
	
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_REFRESH:
		    if(!mDeviceInitialized) {
			refreshDeviceList();
			if(mDevice==null){
			    mHandler.sendEmptyMessageDelayed(MESSAGE_REFRESH,
							     REFRESH_TIMEOUT_MILLIS);
			    break;
			}
		    }
                case MESSAGE_READDATA:
		    Log.d(TAG, "MESSAGE_READDATA");
		    mDoseRate=readOneData()*0.001;
		    Date cdt=new Date();
		    long mdiff=cdt.getTime()-mLastRecordDate.getTime();
		    mDoseRateCaptureDate=cdt;
		    if(mAutoRecord){
			long mrtmsec=mRectime*60*1000;
			if(mdiff >= mrtmsec) {
			    recordOneData();
			    mLastRecordDate.setTime(mLastRecordDate.getTime()+
						    (mdiff/mrtmsec)*mrtmsec);
			}
		    }
		    mDataValueMessage.setText(String.format("%5.3f",mDoseRate));
		    mStatusMessage.setText(getString(R.string.dev_updated));
		    mHandler.sendEmptyMessageDelayed(MESSAGE_READDATA, getReadInterval());
		    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }

    };


    private static PendingIntent mPermissionIntent;
    private void refreshDeviceList()
    {
	mPermissionIntent =
	    PendingIntent.getBroadcast(mContext, 0,
				       new Intent("com.example.radilogwalker.USB_PERMISSION"), 0);

	for (final UsbDevice device : mUsbManager.getDeviceList().values()) {
	    Log.d(TAG, String.format("Vendor ID=0x%04X, Product ID=0x%04X\n",
						 device.getVendorId(),
						 device.getProductId()));
	    if(device.getVendorId()!=UsbId.VENDOR_TECHNOAP) continue;

	    if(!mUsbManager.hasPermission(device)) {
		mUsbManager.requestPermission(device, mPermissionIntent);
	    }

	    final List<UsbSerialDriver> drivers =
		UsbSerialProber.probeSingleDevice(mUsbManager, device);
	    if (drivers.size()==0) {
		Log.d(TAG, "the driver is not available");
		continue;
	    }
	    mDevice=device;
	    mDriver=drivers.get(0);
	    if(init_driver() && setDeviceMesTime(mMesTime)){
		mStatusMessage.setText(getString(R.string.dev_connected));
		return;
	    }
	}
    }

    private void initLocationManager()
    {
	mLocationManager =
	    (LocationManager) getSystemService(Context.LOCATION_SERVICE);

	// Define a listener that responds to location updates
	mLocationListener = new LocationListener() {
		public void onLocationChanged(Location location) {
		    // Called when a new location is found by the network location provider.
		    mCurrentLatitude=location.getLatitude();
		    mCurrentLongitude=location.getLongitude();
		    mGpsCaptureDate=new Date();
		    Log.d(TAG,String.format("Lat.=%f, Long=%f",
					    location.getLatitude(),location.getLongitude()));
		}

		public void onStatusChanged(String provider, int status, Bundle extras) {}
		public void onProviderEnabled(String provider) {}
		public void onProviderDisabled(String provider) {}
	    };
    }

    /* Checks if external storage is available for read and write */
    private boolean isExternalStorageWritable()
    {
	String state = Environment.getExternalStorageState();
	if (Environment.MEDIA_MOUNTED.equals(state)) {
	    return true;
	}
	return false;
    }

    private boolean recordOneData()
    {
	if(mRecordFileWriter==null){
	    if(!isExternalStorageWritable()) return false;
	    File path = Environment.getExternalStoragePublicDirectory
		(getString(R.string.data_directory));
	    File rfile = new File(path, mDataFileName);
	    if(!rfile.exists()){
		try {
		    path.mkdirs();
		    rfile.createNewFile();
		    mRecordFileWriter = new FileWriter(rfile);
		    String str=getString(R.string.csv_tags);
		    mRecordFileWriter.write(str,0,str.length());
		    mRecordFileWriter.write('\n');
		}catch(IOException e){
		    Log.e(TAG, "file can't be created");
		    return false;
		}
	    }else{
		try {
		    mRecordFileWriter=new FileWriter(rfile, true);
		}catch(IOException e){
		    Log.e(TAG, "existing file can't be opened");
		}
	    }
	}

	SimpleDateFormat sdf=new SimpleDateFormat(getString(R.string.datetime_format));
	try{
	    mRecordFileWriter.write(String.format("%f,%f,%f,%s,%s\n",
		    mCurrentLatitude,mCurrentLongitude,
		    mDoseRate,sdf.format(mGpsCaptureDate),
		    sdf.format(mDoseRateCaptureDate)));
	    Log.d(TAG, String.format("%f,%f,%f,%s,%s\n",
		    mCurrentLatitude,mCurrentLongitude,
		    mDoseRate,sdf.format(mGpsCaptureDate),
		    sdf.format(mDoseRateCaptureDate)));
	    mRecordFileWriter.flush();
	    mStatusMessage.setText(getString(R.string.dev_recorded));
	}catch(IOException e){
	    Log.e(TAG, "can't write data into the file");
	    return false;
	}
	return true;
    }

    private void deleteRecFile()
    {
	if(mRecordFileWriter!=null) {
	    try{
		mRecordFileWriter.close();
	    } catch(Exception e) { }
	    mRecordFileWriter=null;
	}
	File path = Environment.getExternalStoragePublicDirectory
	    (getString(R.string.data_directory));
	File rfile = new File(path, mDataFileName);
	if(rfile.exists()) rfile.delete();
    }
	    


    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
	super.onCreate(savedInstanceState);
	setContentView(R.layout.activity_main);

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
	mStatusMessage = (TextView) findViewById(R.id.status_message);
	mContext = getApplicationContext();
	mDataValueMessage = (TextView) findViewById(R.id.data_value);
	mWindow = getWindow();

	mDataFileName=getString(R.string.default_recordfile);
	mDoseRateCaptureDate=new Date();
	mGpsCaptureDate=new Date();
	mLastRecordDate=new Date();

	initLocationManager();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.sendEmptyMessage(MESSAGE_REFRESH);
	mWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

	// Register the listener with the Location Manager to receive location updates
	if(!mLocationListenerUpdated){
	    mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
						    3000, 0, mLocationListener);
	    mLocationListenerUpdated=true;
	}
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeMessages(MESSAGE_REFRESH);
	mHandler.removeMessages(MESSAGE_READDATA);
	mWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

	// Remove the listener you previously added
	if(mLocationListenerUpdated){
	    mLocationManager.removeUpdates(mLocationListener);
	    mLocationListenerUpdated=false;
	}
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
	// Inflate the menu; this adds items to the action bar if it is present.
	getMenuInflater().inflate(R.menu.main, menu);
	mMenu=menu;
	return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
	MeasurementTime mestime=MeasurementTime.MES30S;
	switch (item.getItemId()) {
        case R.id.mestime_30s:
	    mestime=MeasurementTime.MES30S;
	    mMenu.findItem(R.id.mestime_10s).setChecked(false);
	    mMenu.findItem(R.id.mestime_03s).setChecked(false);
	    break;
        case R.id.mestime_10s:
	    mestime=MeasurementTime.MES10S;
	    mMenu.findItem(R.id.mestime_30s).setChecked(false);
	    mMenu.findItem(R.id.mestime_03s).setChecked(false);
	    break;
        case R.id.mestime_03s:
	    mestime=MeasurementTime.MES03S;
	    mMenu.findItem(R.id.mestime_30s).setChecked(false);
	    mMenu.findItem(R.id.mestime_10s).setChecked(false);
	    break;
        case R.id.rectime_auto:
	    mMenu.findItem(R.id.rectime_manual).setChecked(false);
	    setupRecordTimeDialog(mAutoRecord);
	    break;
        case R.id.rectime_manual:
	    mMenu.findItem(R.id.rectime_auto).setChecked(false);
	    mAutoRecord=false;
	    break;
	case R.id.recfile_settings:
	    setupRecordFileDialog();
	    break;
	}
	item.setChecked(true);
	if(mMesTime!=mestime){
	    mMesTime=mestime;
	    if(mDeviceInitialized){
		setDeviceMesTime(mMesTime);
		mHandler.removeMessages(MESSAGE_READDATA);
		mHandler.sendEmptyMessageDelayed(MESSAGE_READDATA, getReadInterval());
	    }
	}
	return true;
    }


    private void setupRecordTimeDialog(final boolean autorec)
    {
	final Dialog dialog = new Dialog(this);
	dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
	dialog.setContentView(R.layout.rectime_setup);
	Button dlOkButton = (Button) dialog.findViewById(R.id.rectime_ok);
	Button dlCancelButton = (Button) dialog.findViewById(R.id.rectime_cancel);
	final EditText hours = (EditText) dialog.findViewById(R.id.rec_hours);
	final EditText minutes = (EditText) dialog.findViewById(R.id.rec_minutes);
	hours.setText(String.format("%d",mRectime/60));
	minutes.setText(String.format("%d",mRectime%60));
	// if button is clicked, close the custom dialog
	dlOkButton.setOnClickListener(new View.OnClickListener() {
		@Override
		public void onClick(View v) {
		    int hs;
		    int ms;
		    try {
			hs = Integer.parseInt(hours.getText().toString());
		    } catch(Exception e) {
			hs = 0;
		    }
		    try {
			ms = Integer.parseInt(minutes.getText().toString());
		    } catch(Exception e) {
			ms = 0;
		    }
		    mRectime=60*hs+ms;
		    mAutoRecord=true;
		    Log.d(TAG, String.format("setupRecordTimeDialog mRectime=%d\n",mRectime));
		    dialog.dismiss();
		}
	    });
	dlCancelButton.setOnClickListener(new View.OnClickListener() {
		@Override
		public void onClick(View v) {
		    dialog.dismiss();
		    if(!autorec){
			mMenu.findItem(R.id.rectime_manual).setChecked(true);
			mMenu.findItem(R.id.rectime_auto).setChecked(false);
		    }
		}
	    });
	dialog.show();
    }

    private void setupRecordFileDialog()
    {
	final Dialog dialog = new Dialog(this);
	dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
	dialog.setContentView(R.layout.recfile_setup);
	Button dlOkButton = (Button) dialog.findViewById(R.id.recfile_ok);
	Button dlCancelButton = (Button) dialog.findViewById(R.id.recfile_cancel);
	final EditText fname = (EditText) dialog.findViewById(R.id.recfname);
	fname.setText(mDataFileName);
	// if button is clicked, close the custom dialog
	dlOkButton.setOnClickListener(new View.OnClickListener() {
		@Override
		public void onClick(View v) {
		    CheckBox cb=(CheckBox)(dialog.findViewById(R.id.rec_discard));
		    if(mDataFileName.equals(fname.getText().toString())){
			if(cb.isChecked()){
			    deleteRecFile();
			}
		    }else{
			if(mRecordFileWriter!=null) {
			    try{
				mRecordFileWriter.close();
			    } catch(Exception e) { }
			    
			    mRecordFileWriter=null;
			}
			mDataFileName=fname.getText().toString();
			if(cb.isChecked()){
			    deleteRecFile();
			}
		    }
		    dialog.dismiss();
		}
	    });
	dlCancelButton.setOnClickListener(new View.OnClickListener() {
		@Override
		public void onClick(View v) {
		    dialog.dismiss();
		}
	    });
	dialog.show();
    }


    private final byte STX=0x02;
    private final byte ETX=0x03;
    private final byte ACK=0x06;
    private final static byte[] HEX_DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };
    private static final int RW_WAIT_MILLIS = 10000;
    private static final byte[] TCOM_DOSERATE = {'8','B','0'};
    private static final byte[] TCOM_MES30SEC = {'8','0','2'};
    private static final byte[] TCOM_MES10SEC = {'8','0','1'};
    private static final byte[] TCOM_MES03SEC = {'8','0','0'};

    private boolean init_driver()
    {
	try {
	    mDriver.open();
	    mDriver.setParameters(115200, 8, UsbSerialDriver.STOPBITS_1,
				  UsbSerialDriver.PARITY_NONE);
	} catch (IOException e) {
	    Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
	    try {
		mDriver.close();
	    } catch (IOException e2) {
                    // Ignore.
	    }
	    mDriver = null;
	    return false;
	}
	mDeviceInitialized=true;
	return true;
    }

    private int send_data(byte[] data) throws IOException 
    {
	int parity=0;
	byte[] sdata;
	if(mDriver==null) return -1;

	sdata=new byte[data.length+4];
	sdata[0]=STX;
	for(int i=0;i<data.length;i++){
	    sdata[i+1]=data[i];
	    parity^=data[i];
	}
	sdata[data.length+1]=ETX;
	sdata[data.length+2]=HEX_DIGITS[(parity>>4)&0x0F];
	sdata[data.length+3]=HEX_DIGITS[parity&0x0F];
	return mDriver.write(sdata, RW_WAIT_MILLIS);
    }

    private int send_wait_ack(byte[] data) throws IOException
    {
	byte[] recd=new byte[1];
        if(send_data(data)<0) return -1;
        if(mDriver.read(recd, RW_WAIT_MILLIS)!=1) return -1;
        if(recd[0]!=ACK) return -1;
	return 0;
    }

    private byte[] rec_data_tout() throws IOException
    {
	byte[] recd=new byte[128];
        byte[] rss=new byte[2];
	int parity=0;
	int rstate=0;
	int reclen;

	// all data comes at one time
	if((reclen=mDriver.read(recd, RW_WAIT_MILLIS))==0) {
	    Log.d(TAG, "Timed Out to receive data\n");
	    return null;
	}
	if(recd[0]!=STX){
	    Log.d(TAG, "No STX\n");
	    return null;
	}
        for(int i=0;i<reclen;i++){
	    switch(rstate){
	    case 0:
		if(recd[i]!=STX) break;
		rstate=1;
		break;
	    case 1:
		if(recd[i]==ETX){
		    rstate=2;
		    break;
		}
		parity^=recd[i];
		break;
	    case 2:
		rss[0]=recd[i];
		rstate=3;
		break;
	    case 3:
		rss[1]=recd[i];
		int rb=Integer.parseInt(new String(rss, 0,2),16);
		if(rb!=parity) {
		    Log.d(TAG, "parity doesn't match\n");
		    return null;
		}
		return Arrays.copyOfRange(recd,1,i-2); // parity matched
	    }
	}
	Log.d(TAG, "Garbage?\n");
	return null;
    }

    private int get_dose_rate(byte[] data)
    {
	if(data[0]!='0' || data[1]!='1') return -1;
	return Integer.parseInt(new String(data, 2, data.length-2),16);
    }

    private boolean setDeviceMesTime(MeasurementTime mestime)
    {
	try {
	    if(send_wait_ack(TCOM_DOSERATE)!=0){
		Log.d(TAG, "can't send doserate command\n");
		return false;
	    }
	    if(send_wait_ack(getMestimeString())!=0){
		Log.d(TAG, "can't send measurement time parameter\n");
		return false;
	    }
	    return true;
	} catch (IOException e) {
	    Log.d(TAG, "IOException\n");
	    return false;
	}
    }
    

    private int readOneData()
    {
	byte[] rdata=null;
	int doserate;
	if(!mDeviceInitialized) return 0;
	try {
	    Log.d(TAG, "Read one data\n");
	    if(send_data(new byte[] {'0','1'})<0){
		Log.d(TAG, "send_data 01 error\n");
		return 0;
	    }
	    rdata=rec_data_tout();
	    if(rdata==null){
		Log.d(TAG, "rec_data_tout error\n");
		return 0;
	    }
	    doserate=get_dose_rate(rdata);
	    if(send_data(new byte[] {'0','2'})<0){
		Log.d(TAG, "send_data 02 error\n");
		return 0;
	    }
	    Log.d(TAG, String.format("Dose Rate = %d\n",doserate));
	    return doserate;
	} catch (IOException e) {
	    Log.d(TAG, "IOException\n");
	}
	return 0;
    }

    /** Called when the user clicks the Read button */
    public void saveData(View view)
    {
	recordOneData();
    }

}

