package com.wse.io;

import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;


public class ImageResizer extends BusModBase implements Handler<Message<JsonObject>> {

	@Override
	public void start() {
		super.start();

		eb.registerHandler(config.getString("address"),this);
		logger.info("BusModBase: Image resizer starts on address: " + config.getString("address"));
	}

	@Override
	public void handle(Message<JsonObject> m) {
		switch(m.body().getString("action")) {
			case "resize" :
				resize(m);
				break;
			default :
				sendError(m, "Invalid or missing action");
		}
	}

	private void resize(Message<JsonObject> m) {
	}

}
