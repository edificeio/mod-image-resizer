package fr.wseduc.resizer;

import org.entcore.common.s3.S3Client;
import org.entcore.common.s3.storage.StorageObject;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import java.net.URI;

public class S3Access implements FileAccess {

    private final S3Client client;

    public S3Access(Vertx vertx, URI uri, String accessKey, String secretKey, String region, String bucket, String ssec) {
        client = new S3Client(vertx, uri, accessKey, secretKey, region, bucket, ssec);
    }

    @Override
    public void read(String src, final Handler<ImageFile> handler) {
        String [] path = parsePath(src);
        if (path == null || path.length != 2) {
            handler.handle(null);
            return;
        }
        client.readFile(path[1], path[0], new Handler<AsyncResult<StorageObject>>() {
            @Override
            public void handle(AsyncResult<StorageObject> event) {
                if (event.succeeded()) {
                    StorageObject f = event.result();
                    handler.handle(new ImageFile(
                            f.getBuffer().getBytes(),
                            f.getFilename(),
                            f.getContentType()
                    ));
                } else {
                    handler.handle(null);
                }
            }
        });
    }

    @Override
    public void write(String dest, ImageFile img, final Handler<String> handler) {
        String [] path = parsePath(dest);
        if (path == null || path.length < 1) {
            handler.handle(null);
            return;
        }
        StorageObject o;
        if (path.length == 2 && path[1] != null && !path[1].trim().isEmpty()) {
            o = new StorageObject(path[1], Buffer.buffer(img.getData()),
                    img.getFilename(), img.getContentType());
        } else {
            o = new StorageObject(Buffer.buffer(img.getData()),
                    img.getFilename(), img.getContentType());
        }
        client.writeFile(o, path[0], new Handler<AsyncResult<String>>() {
            @Override
            public void handle(AsyncResult<String> event) {
                if (event.succeeded()) {
                    handler.handle(event.result());
                } else {
                    handler.handle(null);
                }
            }
        });
    }

    @Override
    public void close() {
        client.close();
    }

    private String[] parsePath(String path) {
        String[] p = path.split("://");
        if (p == null || p.length != 2) {
            return null;
        }
        return p[1].split(":");
    }

}