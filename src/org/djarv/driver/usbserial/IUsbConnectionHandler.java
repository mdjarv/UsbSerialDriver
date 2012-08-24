package org.djarv.driver.usbserial;

public interface IUsbConnectionHandler {
	void onConnected();
	void onDisconnected();
	
	void onMessage(String message);
}
