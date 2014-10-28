/*
 * Copyright © WebServices pour l'Éducation, 2014
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.wseduc.resizer.test.integration.java;

import org.junit.Test;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.testtools.TestVerticle;

import java.io.File;

import static org.vertx.testtools.VertxAssert.assertEquals;
import static org.vertx.testtools.VertxAssert.testComplete;

public class ResizerFSTest extends TestVerticle {

	public static final String SRC_IMG = "file://src/test/resources/img.jpg";
	public static final String ADDRESS = "image.resizer";
	private EventBus eb;

	@Override
	public void start() {
		eb = vertx.eventBus();
		JsonObject config = new JsonObject();
		config.putString("address", ADDRESS);
		config.putString("base-path", new File(".").getAbsolutePath());
		container.deployModule(System.getProperty("vertx.modulename"),
				config, 1, new AsyncResultHandler<String>() {
			public void handle(AsyncResult<String> ar) {
				if (ar.succeeded()) {
					ResizerFSTest.super.start();
				} else {
					ar.cause().printStackTrace();
				}
			}
		});
	}

	@Override
	public void stop() {
		super.stop();
		vertx.fileSystem().delete("wb0x200.jpg", null);
		vertx.fileSystem().delete("wb300x0.jpg", null);
		vertx.fileSystem().delete("wb300x250.jpg", null);
		vertx.fileSystem().delete("wb300x300.jpg", null);
		vertx.fileSystem().delete("crop500x500.jpg", null);
	}

	@Test
	public void testResize() throws Exception {
		JsonObject json = new JsonObject()
				.putString("action", "resize")
				.putString("src", SRC_IMG)
				.putString("dest", "file://wb300x0.jpg")
				.putNumber("width", 300);

		eb.send(ADDRESS, json, new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> reply) {
				assertEquals("ok", reply.body().getString("status"));
				testComplete();
			}
		});
	}

	@Test
	public void testCrop() throws Exception {
		JsonObject json = new JsonObject()
				.putString("action", "crop")
				.putString("src", SRC_IMG)
				.putString("dest", "file://crop500x500.jpg")
				.putNumber("width", 500)
				.putNumber("height", 500)
				.putNumber("x", 50)
				.putNumber("y", 100);

		eb.send(ADDRESS, json, new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> reply) {
				assertEquals("ok", reply.body().getString("status"));
				testComplete();
			}
		});
	}

	@Test
	public void testResizeMultiple() throws Exception {
		JsonArray outputs = new JsonArray()
				.addObject(new JsonObject()
						.putString("dest", "file://wb300x300.jpg")
						.putNumber("width", 300)
						.putNumber("height", 300)
				).addObject(new JsonObject()
						.putString("dest", "file://wb300x250.jpg")
						.putNumber("width", 300)
						.putNumber("height", 250)
				).addObject(new JsonObject()
						.putString("dest", "file://wb0x200.jpg")
						.putNumber("height", 200)
				);
		JsonObject json = new JsonObject()
				.putString("action", "resizeMultiple")
				.putString("src", SRC_IMG)
				.putArray("destinations", outputs);

		eb.send(ADDRESS, json, new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> reply) {
				assertEquals("ok", reply.body().getString("status"));
				testComplete();
			}
		});
	}

}
