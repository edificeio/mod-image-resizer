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

package fr.wseduc.resizer;

import com.mongodb.*;
import com.mongodb.gridfs.GridFS;
import com.mongodb.gridfs.GridFSDBFile;
import com.mongodb.gridfs.GridFSInputFile;
import com.mongodb.util.JSON;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import javax.net.ssl.SSLSocketFactory;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GridFsFileAccess implements FileAccess {

	private final MongoClient mongo;
	private final DB db;

	public GridFsFileAccess(String host, int port, String dbName, String username,
			String password, int poolSize, ReadPreference readPreference, boolean autoConnectRetry,
			int socketTimeout, boolean useSSL, JsonArray seedsProperty) throws UnknownHostException {
		MongoClientOptions.Builder builder = new MongoClientOptions.Builder();
		builder.connectionsPerHost(poolSize);
		builder.autoConnectRetry(autoConnectRetry);
		builder.socketTimeout(socketTimeout);
		builder.readPreference(readPreference);

		if (useSSL) {
			builder.socketFactory(SSLSocketFactory.getDefault());
		}

		if (seedsProperty == null) {
			ServerAddress address = new ServerAddress(host, port);
			mongo = new MongoClient(address, builder.build());
		} else {
			List<ServerAddress> seeds = makeSeeds(seedsProperty);
			mongo = new MongoClient(seeds, builder.build());
		}

		db = mongo.getDB(dbName);
		if (username != null && password != null) {
			db.authenticate(username, password.toCharArray());
		}
	}

	private List<ServerAddress> makeSeeds(JsonArray seedsProperty) throws UnknownHostException {
		List<ServerAddress> seeds = new ArrayList<>();
		for (Object elem : seedsProperty) {
			JsonObject address = (JsonObject) elem;
			String host = address.getString("host");
			int port = address.getInteger("port");
			seeds.add(new ServerAddress(host, port));
		}
		return seeds;
	}

	@Override
	public void read(String src, Handler<ImageFile> handler) {
		String [] path = parsePath(src);
		if (path == null || path.length != 2) {
			handler.handle(null);
			return;
		}
		GridFS fs = new GridFS(db, path[0]);
		GridFSDBFile f = fs.findOne(pathToDbObject(path[1]));
		if (f != null) {
			handler.handle(new ImageFile(f.getInputStream(), f.getFilename(), f.getContentType()));
		} else {
			handler.handle(null);
		}
	}

	@Override
	public void write(String dest, ImageFile img, Handler<String> handler) {
		String [] path = parsePath(dest);
		if (path == null || path.length < 1) {
			handler.handle(null);
			return;
		}
		String id;
		if (path.length == 2 && path[1] != null && !path[1].trim().isEmpty()) {
			id = path[1];
		} else {
			id = UUID.randomUUID().toString();
		}
		GridFS fs = new GridFS(db, path[0]);
		try {
			saveFile(img, id, fs);
		} catch (DuplicateKeyException e) {
			fs.remove(new BasicDBObject("_id", id));
			saveFile(img, id, fs);
		}
		handler.handle(id);
	}

	private GridFSInputFile saveFile(ImageFile img, String id, GridFS fs) {
		GridFSInputFile f = fs.createFile(img.getData());
		f.setId(id);
		f.setContentType(img.getContentType());
		f.setFilename(img.getFilename());
		f.save();
		return f;
	}

	@Override
	public void close() {
		if (mongo != null) {
			mongo.close();
		}
	}

	private DBObject pathToDbObject(String s) {
		String str = new JsonObject().putString("_id", s).encode();
		return (DBObject) JSON.parse(str);
	}

	private String[] parsePath(String path) {
		String[] p = path.split("://");
		if (p == null || p.length != 2) {
			return null;
		}
		return p[1].split(":");
	}

}
