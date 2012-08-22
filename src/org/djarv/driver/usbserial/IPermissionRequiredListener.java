package org.djarv.driver.usbserial;

import android.hardware.usb.UsbDevice;

public interface IPermissionRequiredListener {
	void onPermissionDenied(UsbDevice device);
}
