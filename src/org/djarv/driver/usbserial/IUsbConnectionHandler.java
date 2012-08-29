package org.djarv.driver.usbserial;

public interface IUsbConnectionHandler {
	void onUsbDeviceConnected();
	void onUsbDeviceDisconnected();
	void onUsbDeviceMessage(String message);
}
