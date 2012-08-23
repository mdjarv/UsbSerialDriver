package org.djarv.driver.usbserial;

public interface IUsbConnectionHandler {
	void onConnectionStopped();
	
	void onMessage(String message);
}
