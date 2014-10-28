Module Image Resizer
====================

This module allows you to resize and save images. For reading and storing images, it can use the file system or GridFS.

## Name

The module name is `mod-image-resizer`.

## Configuration

### FileSystem only

The mod-image-resizer module takes the following configuration:

	{
		"address": <address>,
		"base-path": <base-path>
	}

For example:

	{
		"address": "image.resizer",
		"base-path": "/tmp/images"
	}

Let's take a look at each field in turn:

* `address` The main address for the module. Every module has a main address. Defaults to `image.resizer`.
* `base-path` The root folder to read or write images.

### Gridfs

The mod-image-resizer module takes the following configuration:

	{
		"address": <address>,
		"gridfs": {
			"host" : <host>,
			"port" : <port>,
			"db_name" : <db>,
			"username" : <username>,
			"password" : <password>,
			"pool_size" : <pool_size>
		}
	}

For example:

	{
		"address" : "image.resizer",
		"gridfs" : {
			"host": "localhost",
			"port": 27017,
			"db_name": "one_gridfs",
			"pool_size": 10
		}
	}

Let's take a look at each field in turn:

* `address` The main address for the module. Every module has a main address. Defaults to `image.resizer`.
* `base-path` The root folder to read or write images.
* `host` Host name or ip address of the MongoDB instance. Defaults to localhost.
* `port` Port at which the MongoDB instance is listening. Defaults to 27017.
* `db_name` Name of the database in the MongoDB instance to use.
* `username` MongoDB username.
* `password` MongoDB password.
* `pool_size` The number of socket connections the module instance should maintain to the MongoDB server. Default is 10.

### File System + Grids example

	{
		"address" : "image.resizer",
		"base-path": "/tmp/images",
		"gridfs" : {
			"host": "localhost",
			"port": 27017,
			"db_name": "one_gridfs",
			"pool_size": 10
		}
	}

## Operations

The module supports the following operations

### Resize

Resize and store image.

To resize an image send a JSON message to the module main address:

	{
		"action" : "resize",
		"src" : <src>,
		"dest" : <dest>,
		"width" : <width>,
		"height" : <height>,
		"stretch" : <stretch>
	}

Where:
* `src` is the source image. If src start with "file://" image is find in FS. Else if, src start with "gridfs://" image is find in GridFS. This field is mandatory.
* `dest` is the destination image. If dest start with "file://" image is find in FS. Else if, dest start with "gridfs://" image is find in GridFS. This field is mandatory.
* `width` is a number.
* `height` is a number.
* `stretch` is a boolean. When both width and height are specified, if stretch is false (default value) a crop is used to not stretch the image. But, if stretch is true resize not use crop and stretch image.

An example would be:

	{
		"action" : "resize",
		"src" : "file://src/test/resources/img.jpg",
		"dest" : "gridfs://fs",
		"width" : 300
	}

When the query complete successfully, a reply message is sent back to the sender with the following data:

	{
		"status": "ok",
	}

If an error occurs in saving the document a reply is returned:

	{
		"status": "error",
		"message": <message>
	}

Where
* `message` is an error message.

### Crop

Crop and store image.

Resize and store image.

To crop an image send a JSON message to the module main address:

	{
		"action" : "crop",
		"src" : <src>,
		"dest" : <dest>,
		"width" : <width>,
		"height" : <height>,
		"x" : <x>,
		"y" : <y>
	}

Where:
* `src` is the source image. If src start with "file://" image is find in FS. Else if, src start with "gridfs://" image is find in GridFS. This field is mandatory.
* `dest` is the destination image. If dest start with "file://" image is find in FS. Else if, dest start with "gridfs://" image is find in GridFS. This field is mandatory.
* `width` is a number.
* `height` is a number.
* `x` is a number.
* `y` is a number.

An example would be:

	{
		"action" : "crop",
		"src" : "file://src/test/resources/img.jpg",
		"dest" : "file://crop500x500.jpg",
		"width" : 500,
		"height": 500,
		"x" : 50,
		"y" : 100
	}

When the query complete successfully, a reply message is sent back to the sender with the following data:

	{
		"status": "ok",
	}

If an error occurs in saving the document a reply is returned:

	{
		"status": "error",
		"message": <message>
	}

Where
* `message` is an error message.

### Resize image in multiples sizes

Resize and store image in multiples sizes.

To resize image in multiples sizes send a JSON message to the module main address:

	{
		"action" : "resizeMultiple",
		"src" : <src>,
		"destinations" : <destinations>
	}

Where:
* `src` is the source image. If src start with "file://" image is find in FS. Else if, src start with "gridfs://" image is find in GridFS. This field is mandatory.
* `destinations` is an array of object with same attribute than resize. This field is mandatory.

An example would be:

	{
		"action" : "resizeMultiple",
		"src" : "file://src/test/resources/img.jpg",
		"destinations" : [
			{
				"dest" : "file://wb300x300.jpg",
				"width" : 300,
				"height" : 300
			},{
				"dest" : "file://wb300x250.jpg",
				"width" : 300,
				"height" : 250
			},{
				"dest" : "file://wb0x200.jpg",
				"height" : 200
			}
		]
	}

When the query complete successfully, a reply message is sent back to the sender with the following data:

	{
		"status": "ok",
	}

If an error occurs in saving the document a reply is returned:

	{
		"status": "error",
		"message": <message>
	}

Where
* `message` is an error message.
