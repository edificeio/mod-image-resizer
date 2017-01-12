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

import com.mongodb.DB;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.gridfs.GridFS;
import com.mongodb.gridfs.GridFSDBFile;
import com.mongodb.util.JSON;
import fr.wseduc.swift.SwiftClient;
import fr.wseduc.swift.storage.StorageObject;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import static org.vertx.testtools.VertxAssert.assertEquals;
import static org.vertx.testtools.VertxAssert.fail;
import static org.vertx.testtools.VertxAssert.testComplete;

public class ResizerFSTest extends TestVerticle {

	public static final String ADDRESS = "image.resizer";
	public static final String DB_NAME = "resizer_tests";
	private String basePath;
	public String SRC_IMG;
	private EventBus eb;
	private DB db;
	private MongoClient mongo;
	private SwiftClient swiftClient;

	@Override
	public void start() {
		eb = vertx.eventBus();
		this.basePath = new File("").getAbsolutePath() + File.separator;
		this.SRC_IMG = "file://"+ basePath + ":src/test/resources/img.jpg";
		System.out.println("basepath : " + basePath);
		final JsonObject config = new JsonObject();
		config.putString("address", ADDRESS);
		config.putBoolean("fs-flat", true);
		config.putString("base-path", basePath);
		config.putObject("gridfs", new JsonObject().putString("db_name", DB_NAME));
		config.putBoolean("allow-image-enlargement", true);
		JsonObject swiftConfig = new JsonObject()
				.putString("uri", "http://172.17.0.2:8080")
				.putString("user", "test:tester")
				.putString("key", "testing");
		config.putObject("swift", swiftConfig);
		try {
			mongo = new MongoClient();
			db = mongo.getDB(DB_NAME);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		try {
			swiftClient = new SwiftClient(vertx, new URI(swiftConfig.getString("uri")));
			swiftClient.authenticate(swiftConfig.getString("user"), swiftConfig.getString("key"),
					new AsyncResultHandler<Void>() {
						@Override
						public void handle(AsyncResult<Void> event) {
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
					});
		} catch (URISyntaxException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void stop() {
		super.stop();
		if (mongo != null) {
			mongo.close();
		}
		vertx.fileSystem().delete("wb0x200.jpg", null);
		vertx.fileSystem().delete("wb300x0.jpg", null);
		vertx.fileSystem().delete("wb300x250.jpg", null);
		vertx.fileSystem().delete("wb300x300.jpg", null);
		vertx.fileSystem().delete("crop500x500.jpg", null);
		vertx.fileSystem().delete("compressed.jpg", null);
	}

	@Test
	public void testResize() throws Exception {
		JsonObject json = new JsonObject()
				.putString("action", "resize")
				.putString("src", SRC_IMG)
				.putString("dest", "file://" + basePath + ":wb300x0.jpg")
				.putNumber("width", 300);

		eb.send(ADDRESS, json, new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> reply) {
				assertEquals("ok", reply.body().getString("status"));
				testComplete();
			}
		});
	}

	@Test
	public void testResizeStoreMongo() throws Exception {
		JsonObject json = new JsonObject()
				.putString("action", "resize")
				.putString("src", SRC_IMG)
				.putString("dest", "gridfs://fs")
				.putNumber("width", 300);

		eb.send(ADDRESS, json, new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> reply) {
				assertEquals("ok", reply.body().getString("status"));
				String id = reply.body().getString("output");
				GridFS fs = new GridFS(db, "fs");
				GridFSDBFile f = fs.findOne((DBObject) JSON.parse("{\"_id\":\"" + id + "\"}"));
				assertEquals("image/jpeg", f.getContentType());
				testComplete();
			}
		});
	}

	@Test
	public void testResizeStoreSwift() throws Exception {
		JsonObject json = new JsonObject()
				.putString("action", "resize")
				.putString("src", SRC_IMG)
				.putString("dest", "swift://testcontainer")
				.putNumber("width", 300);

		eb.send(ADDRESS, json, new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> reply) {
				assertEquals("ok", reply.body().getString("status"));
				String id = reply.body().getString("output");
				swiftClient.readFile(id, "testcontainer", new AsyncResultHandler<StorageObject>() {
					@Override
					public void handle(AsyncResult<StorageObject> event) {
						if (event.succeeded()) {
							assertEquals("image/jpeg", event.result().getContentType());
							assertEquals("img.jpg", event.result().getFilename());
						} else {
							fail(event.cause().getMessage());
						}
						testComplete();
					}
				});
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

	@Test
	public void testCompress() throws Exception {
		JsonObject json = new JsonObject()
				.putString("action", "compress")
				.putString("src", SRC_IMG)
				.putString("dest", "file://compressed.jpg")
				.putNumber("quality", 0.5);

		eb.send(ADDRESS, json, new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> reply) {
				assertEquals("ok", reply.body().getString("status"));
				testComplete();
			}
		});
	}


}
