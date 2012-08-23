package org.djarv.driver.usbserial;

import android.hardware.usb.UsbDevice;

public interface IPermissionListener {
	void onPermissionDenied(UsbDevice device);
}
