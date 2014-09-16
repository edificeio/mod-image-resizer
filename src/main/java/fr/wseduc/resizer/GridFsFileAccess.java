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
import org.vertx.java.core.json.JsonObject;

import java.net.UnknownHostException;
import java.util.UUID;

public class GridFsFileAccess implements FileAccess {

	private final MongoClient mongo;
	private final DB db;

	public GridFsFileAccess(String host, int port, String dbName, String username,
			String password, int poolSize) throws UnknownHostException {
		MongoClientOptions.Builder builder = new MongoClientOptions.Builder();
		builder.connectionsPerHost(poolSize);
		ServerAddress address = new ServerAddress(host, port);
		mongo = new MongoClient(address, builder.build());
		db = mongo.getDB(dbName);
		if (username != null && password != null) {
			db.authenticate(username, password.toCharArray());
		}
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
		GridFS fs = new GridFS(db, path[0]);
		GridFSInputFile f = fs.createFile(img.getData());
		String id;
		if (path.length == 2 && path[1] != null && !path[1].trim().isEmpty()) {
			id = path[1];
		} else {
			id = UUID.randomUUID().toString();
		}
		f.setId(id);
		f.setContentType(img.getContentType());
		f.setFilename(img.getFilename());
		f.save();
		handler.handle(id);
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
