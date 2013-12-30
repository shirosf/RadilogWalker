package com.example.radilogwalker;

import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.Dialog;
import android.widget.Button;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.widget.TextView;
import android.widget.EditText;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.driver.UsbId;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;

public class MainActivity extends Activity
{
    private UsbManager mUsbManager;
    private TextView mStatusMessage;
    private TextView mDataValueMessage;
    private UsbDevice mDevice=null;
    private UsbSerialDriver mDriver=null;
    private int mRectime=0;
    private Context mContext;

    private static final int MESSAGE_REFRESH = 101;
    private static final int MESSAGE_READDATA = 102;
    private static final long REFRESH_TIMEOUT_MILLIS = 5000;
    private final String TAG = MainActivity.class.getSimpleName();
    private enum MeasurementTime { MES30S, MES10S, MES03S }
    private MeasurementTime mMesTime = MeasurementTime.MES30S;
    private Menu mMenu;
    private boolean mDeviceInitialized = false;

    // use 3sec read interval for all measurement time
    private int getReadInterval()
    {
	switch(mMesTime){
	case MES30S:
	    return 3000;
	case MES10S:
	    return 3000;
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
	    int doserate;
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
		    doserate=readOneData();
		    mDataValueMessage.setText(String.format("%5.3f",doserate*0.001));
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
	    if(device.getVendorId()!=UsbId.VENDOR_TECHNOAP  ||
	       device.getProductId()!=UsbId.TECHNOAP_TC200S) continue;

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
		mStatusMessage.setText("接続中");
		return;
	    }
	}
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.sendEmptyMessage(MESSAGE_REFRESH);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeMessages(MESSAGE_REFRESH);
	mHandler.removeMessages(MESSAGE_READDATA);
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
	    setupRecordTimeDialog(this);
	    break;
        case R.id.rectime_manual:
	    mMenu.findItem(R.id.rectime_auto).setChecked(false);
	    mRectime=0;
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


    private void setupRecordTimeDialog(Context context)
    {
	final Dialog dialog = new Dialog(context);
	dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
	dialog.setContentView(R.layout.rectime_setup);
	Button dlOkButton = (Button) dialog.findViewById(R.id.rectime_ok);
	Button dlCancelButton = (Button) dialog.findViewById(R.id.rectime_cancel);
	// if button is clicked, close the custom dialog
	dlOkButton.setOnClickListener(new View.OnClickListener() {
		@Override
		public void onClick(View v) {
		    TextView hours = (TextView) findViewById(R.id.rec_hours);
		    TextView minutes = (TextView) findViewById(R.id.rec_minutes);
		    String hs = hours.getText().toString();
		    String ms = minutes.getText().toString();
		    mRectime=60*Integer.parseInt(hs)+Integer.parseInt(ms);
		    Log.d(TAG, String.format("setupRecordTimeDialog mRectime=%d\n",mRectime));
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
}

