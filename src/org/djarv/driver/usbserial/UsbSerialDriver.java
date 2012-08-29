package org.djarv.driver.usbserial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.util.Log;

public class UsbSerialDriver {
	private class PermissionReceiver extends BroadcastReceiver {
		private final IPermissionListener mPermissionListener;

		public PermissionReceiver(IPermissionListener permissionListener) {
			mPermissionListener = permissionListener;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			mContext.unregisterReceiver(this);
			if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
				if (!intent.getBooleanExtra(
						UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
					mPermissionListener.onPermissionDenied((UsbDevice) intent
							.getParcelableExtra(UsbManager.EXTRA_DEVICE));
				} else {
					Log.i(TAG, "Permission granted");
					UsbDevice dev = (UsbDevice) intent
							.getParcelableExtra(UsbManager.EXTRA_DEVICE);
					if (dev != null) {
						doConnect(dev);
					} else {
						Log.e(TAG, "Device not present!");
					}
				}
			}
		}

	}

	private class UsbDeviceInboundThread extends Thread {
		private final UsbDeviceConnection mUsbDeviceConnection;
		private final UsbEndpoint mEndpoint;

		public UsbDeviceInboundThread(UsbDeviceConnection usbDeviceConnection,
				UsbEndpoint endpoint) {
			mUsbDeviceConnection = usbDeviceConnection;
			mEndpoint = endpoint;
		}

		@Override
		public void run() {
			mInboundLoop = true;

			// TODO set up buffer size in driver
			int length = 1024;

			byte[] buffer = new byte[length];

			// Start loop
			for (;;) {
				// Log.d(TAG, "Inbound loop");
				int responseSize = mUsbDeviceConnection.bulkTransfer(mEndpoint,
						buffer, length, mInboundTimeout);
				if (responseSize > 0) {
					if (mConnectionHandler != null)
						mConnectionHandler.onUsbDeviceMessage(new String(buffer, 0,
								responseSize));
				} else {
					try {
						// Wait a second for new data
						sleep(1000);
					} catch (InterruptedException e) {
						mStop = true;
					}
				}
				if (mStop) {
					// mConnectionHandler.onMessage("Inbound connection terminated");
					break;
				}
			}

			Log.d(TAG, "Inbound loop ended");
			mInboundLoop = false;
		}
	}

	private class UsbDeviceOutboundThread extends Thread {
		private final UsbDeviceConnection mUsbDeviceConnection;
		private final UsbEndpoint mEndpoint;

		public UsbDeviceOutboundThread(UsbDeviceConnection usbDeviceConnection,
				UsbEndpoint endpoint) {
			mUsbDeviceConnection = usbDeviceConnection;
			mEndpoint = endpoint;
		}

		@Override
		public void run() {
			mOutboundLoop = true;
			UsbRequest usbRequest = new UsbRequest();
			usbRequest.initialize(mUsbDeviceConnection, mEndpoint);

			for (;;) {
				Log.d(TAG, "Outbound loop");
				try {
					String message = mOutgoingMessages.take();
					Log.d(TAG, "Sending message: " + message);

					int result = mUsbDeviceConnection.bulkTransfer(mEndpoint,
							message.getBytes(), message.getBytes().length,
							mOutboundTimeout);
					Log.d(TAG, "Sent " + result + " bytes");
				} catch (InterruptedException e) {
					// mConnectionHandler.onMessage("Outbound connection terminated");
					break;
				}
			}

			Log.d(TAG, "Outbound loop ended");

			mOutboundLoop = false;
		}
	}

	private static final String TAG = UsbSerialDriver.class.getSimpleName();

	private static final int FLAG_BAUD_RATE = 32;
	public static final Map<String, String> KNOWN_DEVICES;

	private static final String ACTION_USB_PERMISSION = "org.djarv.driver.usbserial.USB_PERMISSION";
	static {
		Map<String, String> aMap = new HashMap<String, String>();
		aMap.put("2341:0010", "Arduino Mega 2560");
		// aMap.put("1EAF:0003", "LeafLabs Maple (r5) Perpetual Bootloader");
		aMap.put("1EAF:0004", "LeafLabs Maple (r5)");
		KNOWN_DEVICES = Collections.unmodifiableMap(aMap);
	}
	private static String formatDeviceId(UsbDevice device) {
		return String.format("%04X:%04X", device.getVendorId(),
				device.getProductId());
	}

	public static String getPrettyDeviceName(UsbDevice device) {
		String deviceId = formatDeviceId(device);
		String name = KNOWN_DEVICES.get(deviceId);

		if (name == null)
			name = "Unknown";

		return String.format("%s (%s)", name, deviceId);
	}

	private static void setBaudRate(final UsbDeviceConnection connection,
			int baud) {

		byte[] msg = { (byte) (baud & 0xff), (byte) ((baud >> 8) & 0xff),
				(byte) ((baud >> 16) & 0xff), (byte) ((baud >> 24) & 0xff),

				(byte) 0, // stopBits
				(byte) 0, // parity
				(byte) 8 }; // dataBits

		// TODO A lot of magic numbers here
		connection.controlTransfer(0x21, FLAG_BAUD_RATE, 0, 0, msg, 7, 0);
	}

	private Context mContext;

	private UsbManager mUsbManager;

	private IUsbConnectionHandler mConnectionHandler;

	private ArrayList<UsbDevice> mDeviceList = new ArrayList<UsbDevice>();
	private LinkedBlockingQueue<String> mOutgoingMessages = new LinkedBlockingQueue<String>();

	private int mOutboundTimeout = 0;

	private int mInboundTimeout = 0;

	private boolean mStop = false;

	private boolean mInboundLoop = false;

	private boolean mOutboundLoop = false;
	private BroadcastReceiver mPermissionReceiver = new PermissionReceiver(
			new IPermissionListener() {
				@Override
				public void onPermissionDenied(UsbDevice d) {
					Log.w(TAG, "Permission denied on " + d.getDeviceId());
				}
			});
	private UsbDeviceOutboundThread mOutboundThread;

	private UsbDeviceInboundThread mInboundThread;

	public UsbSerialDriver(Context context,
			IUsbConnectionHandler connectionHandler) {
		this.mContext = context;
		this.mConnectionHandler = connectionHandler;
		initialize();
	}

	/**
	 * Attempt to connect to a USB device, will trigger
	 * onPermissionDenied(device) if we do not have permission. After
	 * permissions have been granted from the calling activity, retry
	 * connectToDevice(device, permissionRequired).
	 * 
	 * If a connection already exists, it will disconnect and stop threads
	 * first.
	 * 
	 * @param device
	 *            The device to connect
	 * @param permissionRequired
	 *            Interface to handle permission requests
	 */
	public void connect(UsbDevice device) {
		if (mInboundLoop || mOutboundLoop)
			disconnect();

		mStop = false;

		if (!mUsbManager.hasPermission(device)) {
			UsbManager usbman = (UsbManager) mContext
					.getSystemService(Context.USB_SERVICE);
			PendingIntent pi = PendingIntent.getBroadcast(mContext, 0,
					new Intent(ACTION_USB_PERMISSION), 0);
			mContext.registerReceiver(mPermissionReceiver, new IntentFilter(
					ACTION_USB_PERMISSION));
			usbman.requestPermission(device, pi);
		} else
			doConnect(device);
	}

	public void disconnect() {
		mStop = true;
		boolean connectionWasActive = false;

		if (mInboundLoop) {
			connectionWasActive = true;
			mInboundThread.interrupt();
			try {
				mInboundThread.join();
				mInboundThread = null;
			} catch (InterruptedException e) {
				Log.w(TAG, "Inbound thread shutdown interrupted", e);
			}
		}

		if (mOutboundLoop) {
			connectionWasActive = true;
			mOutboundThread.interrupt();
			try {
				mOutboundThread.join();
				mOutboundThread = null;
			} catch (InterruptedException e) {
				Log.w(TAG, "Outbound thread shutdown interrupted", e);
			}
		}

		if (connectionWasActive && mConnectionHandler != null)
			mConnectionHandler.onUsbDeviceDisconnected();
	}

	private boolean doConnect(UsbDevice device) {
		mStop = false;

		final UsbDeviceConnection connection = mUsbManager.openDevice(device);

		if (!connection.claimInterface(device.getInterface(1), true)) {
			return false;
		}

		// TODO Describe command below. Activate serial interface?
		connection.controlTransfer(0x21, 34, 0, 0, null, 0, 0);

		// Set baud rate
		setBaudRate(connection, 115200);

		UsbEndpoint epIN = null;
		UsbEndpoint epOUT = null;

		UsbInterface usbIf = device.getInterface(1);
		for (int i = 0; i < usbIf.getEndpointCount(); i++) {
			if (usbIf.getEndpoint(i).getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
				if (usbIf.getEndpoint(i).getDirection() == UsbConstants.USB_DIR_IN)
					epIN = usbIf.getEndpoint(i);
				else
					epOUT = usbIf.getEndpoint(i);
			}
		}

		if (epIN != null) {
			mInboundThread = new UsbDeviceInboundThread(connection, epIN);
			mInboundThread.start();
		}

		if (epOUT != null) {
			mOutboundThread = new UsbDeviceOutboundThread(connection, epOUT);
			mOutboundThread.start();
		}

		if(mConnectionHandler != null)
			mConnectionHandler.onUsbDeviceConnected();
		return true;
	}

	public UsbDevice getCompatibleDevice() {
		for (UsbDevice device : getUsbDevices()) {
			if (KNOWN_DEVICES.containsKey(formatDeviceId(device)))
				return device;
		}

		return null;
	}

	/**
	 * Refresh and get a full list of USB devices
	 * 
	 * @return A list of USB devices available
	 */
	public List<UsbDevice> getUsbDevices() {
		refreshDeviceList();
		return mDeviceList;
	}

	private void initialize() {
		mUsbManager = (UsbManager) mContext
				.getSystemService(Context.USB_SERVICE);
		refreshDeviceList();
	}

	public void queueMessage(String message) {
		try {
			mOutgoingMessages.put(message);
			// Log.d(TAG, "Message queued: "+message);
		} catch (InterruptedException e) {
			Log.w(TAG, "Message not queued, thread was interrupted");
		}
	}

	private void refreshDeviceList() {
		Map<String, UsbDevice> devices = mUsbManager.getDeviceList();

		mDeviceList.clear();
		mDeviceList.addAll(devices.values());
		Log.i(TAG, "Found " + mDeviceList.size() + " devices");
	}

	public void setUsbConnectionHandler(IUsbConnectionHandler connectionHandler) {
		mConnectionHandler = connectionHandler;
	}
}
