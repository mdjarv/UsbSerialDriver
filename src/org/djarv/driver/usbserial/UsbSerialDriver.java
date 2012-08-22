package org.djarv.driver.usbserial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.util.Log;

public class UsbSerialDriver {

	private static final String TAG = UsbSerialDriver.class.getSimpleName();
	private static final int FLAG_BAUD_RATE = 32;

	public static final Map<String, String> KNOWN_DEVICES;
	static {
		Map<String, String> aMap = new HashMap<String, String>();
		aMap.put("2341:0010", "Arduino Mega 2560");
		// aMap.put("1EAF:0003", "LeafLabs Maple (r5) Perpetual Bootloader");
		aMap.put("1EAF:0004", "LeafLabs Maple (r5)");
		KNOWN_DEVICES = Collections.unmodifiableMap(aMap);
	}

	private Context context;
	private UsbManager usbManager;
	private final IUsbConnectionHandler connectionHandler;
	private ArrayList<UsbDevice> deviceList = new ArrayList<UsbDevice>();
	private boolean stop = false;
	

	public UsbSerialDriver(Context context, IUsbConnectionHandler connectionHandler) {
		this.context = context;
		this.connectionHandler = connectionHandler;
		initialize();
	}

	private void initialize() {
		usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
		refreshDeviceList();
	}

	public void connectToDevice(UsbDevice device,
			IPermissionRequiredListener permissionRequired) {

		if (!usbManager.hasPermission(device))
			permissionRequired.onPermissionDenied(device);
		else
			connect(device);
	}

	public List<UsbDevice> getUsbDevices() {
		refreshDeviceList();
		return deviceList;
	}

	public UsbDevice getCompatibleDevice() {
		for (UsbDevice device : deviceList) {
			if (KNOWN_DEVICES.containsKey(formatDeviceId(device)))
				return device;
		}

		return null;
	}

	private void refreshDeviceList() {
		Map<String, UsbDevice> devices = usbManager.getDeviceList();

		deviceList.clear();
		deviceList.addAll(devices.values());
		Log.i(TAG, "Found " + deviceList.size() + " devices");
	}

	public static String getPrettyDeviceName(UsbDevice device) {
		String deviceId = formatDeviceId(device);
		String name = KNOWN_DEVICES.get(deviceId);

		if (name == null)
			name = "Unknown";

		return String.format("%s (%s)", name, deviceId);
	}

	private static String formatDeviceId(UsbDevice device) {
		return String.format("%04X:%04X", device.getVendorId(),
				device.getProductId());
	}

	private void connect(UsbDevice device) {
		UsbDeviceThread deviceThread = new UsbDeviceThread(device);
		deviceThread.start();
	}

	public void disconnect() {
		// TODO Stub
	}

	private class UsbDeviceThread extends Thread {
		private final UsbDevice device;

		public UsbDeviceThread(UsbDevice device) {
			this.device = device;
		}

		@Override
		public void run() {
			// Running
			final UsbDeviceConnection conn = usbManager.openDevice(device);
			if (!conn.claimInterface(device.getInterface(1), true)) {
				return;
			}

			// Arduino Serial usb Conv
			conn.controlTransfer(0x21, 34, 0, 0, null, 0, 0);

			// Set baud rate
			setBaudRate(conn, 115200);

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

			UsbRequest usbRequest = new UsbRequest();
			usbRequest.initialize(conn, epIN);

			// addMessage("Endpoint set up");

			int length = 256;

			byte[] buffer = new byte[length];
			StringBuffer sb = new StringBuffer();

			// Start loop
			for (;;) {
				int responseSize = conn.bulkTransfer(epIN, buffer, length, 0);
				if (responseSize > 0) {
					// Example string: $S,562226333,155331283,0,0,0*
					
					// FIXME A lot of APHW specific stuff here, move this out of the driver
					for(int i = 0; i < responseSize; i++) {
						sb.append(String.format("%c", buffer[i]));
					}
					if(sb.toString().trim().endsWith("*")) {
						// TODO fire message event -- addMessage(sb.toString().trim());
						sb = new StringBuffer();
					}
				}

				if (stop) {
					connectionHandler.onConnectionStopped();
					return;
				}
			}
		}

		private void setBaudRate(final UsbDeviceConnection connection, int baud) {

			byte[] msg = { (byte) (baud & 0xff),
					(byte) ((baud >> 8) & 0xff),
					(byte) ((baud >> 16) & 0xff),
					(byte) ((baud >> 24) & 0xff),

					(byte) 0,   // stopBits
					(byte) 0,   // parity
					(byte) 8 }; // dataBits
			
			// TODO A lot of magic numbers here
			connection.controlTransfer(0x21, FLAG_BAUD_RATE, 0, 0, msg, 7, 0);
		}
	}
}
