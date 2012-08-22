package org.djarv.driver.usbserial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

public class UsbSerialDriver {

	private static final String TAG = UsbSerialDriver.class.getSimpleName();
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
	private ArrayList<UsbDevice> deviceList = new ArrayList<UsbDevice>();

	public UsbSerialDriver(Context context) {
		this.context = context;
		initialize();
	}
	
	private void initialize() {
		usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
		refreshDeviceList();
	}

	public List<UsbDevice> getUsbDevices() {
		refreshDeviceList();
		return deviceList;
	}
	
	public UsbDevice getCompatibleDevice() {
		for(UsbDevice device : deviceList) {
			if(KNOWN_DEVICES.containsKey(formatDeviceId(device)))
				return device;
		}
		
		return null;
	}

	private void refreshDeviceList() {
		Map<String, UsbDevice> devices = usbManager.getDeviceList();
		
		deviceList.clear();
		deviceList.addAll(devices.values());
		Log.i(TAG, "Found "+deviceList.size()+" devices");
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

	public void connect(UsbDevice device) {
		// TODO Stub
	}

	public void disconnect() {
		// TODO Stub
	}
}
