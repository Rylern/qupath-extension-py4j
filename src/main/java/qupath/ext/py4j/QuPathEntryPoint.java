/*-
 * Copyright 2022 QuPath developers,  University of Edinburgh
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


package qupath.ext.py4j;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import com.google.common.collect.Lists;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import ij.ImagePlus;
import ij.io.FileSaver;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.SummaryMeasurementTableCommand;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.gui.tools.GuiTools.SnapshotType;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.io.FeatureCollection;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Entry point for use with a Py4J Gateway.
 * This provides useful methods to work with QuPath from Python.
 * 
 * @author Pete Bankhead
 */
public class QuPathEntryPoint extends QPEx {
	
	public static String getExtensionVersion() {
		return GeneralTools.getPackageVersion(QuPathEntryPoint.class);
	}
	
	public static byte[] snapshot(QuPathGUI qupath) throws IOException {
		return getImageBytes(GuiTools.makeSnapshot(qupath, SnapshotType.MAIN_SCENE), "png");
	}

	public static byte[] snapshot(QuPathViewer viewer) throws IOException {
		return getImageBytes(GuiTools.makeViewerSnapshot(viewer), "png");
	}
	
	public static String getDetectionMeasurementTable(ImageData<?> imageData) {
		if (imageData == null)
			return "";
		return getMeasurementTable(imageData, imageData.getHierarchy().getDetectionObjects());
	}
	
	public static String getAnnotationMeasurementTable(ImageData<?> imageData) {
		if (imageData == null)
			return "";
		return getMeasurementTable(imageData, imageData.getHierarchy().getAnnotationObjects());
	}
	
	public static String getMeasurementTable(ImageData<?> imageData, Collection<? extends PathObject> pathObjects) {
		if (imageData == null || pathObjects == null || pathObjects.isEmpty())
			return "";
		var table = new ObservableMeasurementTableData();
		table.setImageData(imageData, pathObjects);
		return SummaryMeasurementTableCommand.getTableModelString(table, "\t", Collections.emptyList());
	}
		
	/**
	 * Create a {@link PathObject} from a GeoJSON representation.
	 * @param geoJson
	 * @return
	 */
	public static PathObject toPathObject(String geoJson) {
		return GsonTools.getInstance().fromJson(geoJson, PathObject.class);
	}

	/**
	 * Create a {@link ROI} from a GeoJSON representation.
	 * @param geoJson
	 * @return
	 */
	public static ROI toROI(String geoJson) {
		return GsonTools.getInstance().fromJson(geoJson, ROI.class);
	}

	public static List<PathObject> toPathObjects(String geoJson) {
		var gson = GsonTools.getInstance();
		return toPathObjects(gson.fromJson(geoJson, JsonElement.class));
	}

	public static List<PathObject> toPathObjects(JsonElement jsonElement) {
		var gson = GsonTools.getDefaultBuilder()
				.registerTypeAdapter(ImagePlane.class, Py4JImagePlaneTypeAdapter.INSTANCE)
				.create();
		if (jsonElement.isJsonArray()) {
			return toStream(jsonElement.getAsJsonArray().asList(), 10)
					.flatMap(e -> toPathObjects(e).stream())
					.collect(Collectors.toList());
		}
		if (jsonElement.isJsonObject()) {
			JsonObject jsonObject = jsonElement.getAsJsonObject();
			if (jsonObject.size() == 0)
				return Collections.emptyList();
			if (jsonObject.has("features")) {
				return toPathObjects(jsonObject.get("features"));
			} else {
				stripNulls(jsonObject);
				return Collections.singletonList(gson.fromJson(jsonObject, PathObject.class));
			}
		} else
			return Collections.emptyList();
	}

	/**
	 * QuPath's v0.4.3 GeoJSON deserialization failed on some null entries, so make sure these are removed
	 * @param jsonObject
	 */
	private static void stripNulls(JsonObject jsonObject) {
		for (String key : new ArrayList<>(jsonObject.keySet())) {
			JsonElement member = jsonObject.get(key);
			if (member == null || member.isJsonNull())
				jsonObject.remove(key);
			else if (member.isJsonObject())
				stripNulls(member.getAsJsonObject());
		}
	}

	
	public static List<ROI> toROIs(String geoJson) {
		return GsonTools.getInstance().fromJson(geoJson, 
				new TypeToken<List<ROI>>() {}.getType()
				);
	}
	
	/**
	 * Convert a collection of PathObjects to a GeoJSON FeatureCollection.
	 * If there is a chance the resulting string will be too long, prefer instead
	 * {@link #toFeatureCollections(Collection, int)} to partition objects into separate feature collections.
	 * @param pathObjects
	 * @return
	 */
	public static String toFeatureCollection(Collection<? extends PathObject> pathObjects) {
		var collection = FeatureCollection.wrap(pathObjects);
		return GsonTools.getInstance().toJson(collection);
	}

	/**
	 * Create a GeoJSON FeatureCollection from a collection of PathObjects, partitioning into separate collections.
	 * This can be useful for performance reasons, and also to avoid the character limit for strings in Java and Python.
	 * @param pathObjects
	 * @param chunkSize
	 * @return
	 */
	public static List<String> toFeatureCollections(Collection<? extends PathObject> pathObjects, int chunkSize) {
		return toStream(Lists.partition(new ArrayList<>(pathObjects), chunkSize), 4)
				.map(QuPathEntryPoint::toFeatureCollection).collect(Collectors.toList());
	}

	public static List<String> toGeoJsonFeatureList(Collection<? extends PathObject> pathObjects) {
		var gson = GsonTools.getInstance();
		return toStream(pathObjects, 100).map(p -> gson.toJson(p)).collect(Collectors.toList());
	}

	public static List<String> getObjectIds(Collection<? extends PathObject> pathObjects) {
		return pathObjects.stream().map(p -> p.getID().toString()).collect(Collectors.toList());
	}

	public static List<String> getMeasurementNames(Collection<? extends PathObject> pathObjects) {
		return pathObjects.stream()
				.flatMap(p -> p.getMeasurementList().getMeasurementNames().stream())
				.distinct()
				.collect(Collectors.toList());
	}

	public static List<Double> getMeasurements(Collection<? extends PathObject> pathObjects, String name) {
		return pathObjects.stream().map(p -> p.getMeasurements().getOrDefault(name, null)).collect(Collectors.toList());
	}

	private static <T> Stream<T> toStream(Collection<T> collection, int minSizeForParallelism) {
		if (collection.size() >= minSizeForParallelism)
			return collection.parallelStream();
		else
			return collection.stream();
	}

	public static String toGeoJsonFeature(PathObject pathObject) {
		return GsonTools.getInstance().toJson(pathObject);
	}
	
	public static String toGeoJson(ROI roi) {
		return GsonTools.getInstance().toJson(roi);
	}
	

	public static byte[] getTiffStack(ImageServer<BufferedImage> server, double downsample) throws IOException {
		return getTiffStack(server, downsample, 0, 0, server.getWidth(), server.getHeight());
	}

	public static byte[] getTiffStack(ImageServer<BufferedImage> server, double downsample, int x, int y, int width, int height) throws IOException {
		return getTiffStack(server, downsample, x, y, width, height, 0, 0);		
	}

	public static byte[] getTiffStack(ImageServer<BufferedImage> server, double downsample, int x, int y, int width, int height, int z, int t) throws IOException {
		return getTiffStack(server, RegionRequest.createInstance(server.getPath(), 
							downsample, x, y, width, height, z, t));	
	}
	
	public static byte[] getTiffStack(ImageServer<BufferedImage> server, RegionRequest request) throws IOException {
		var imp = IJTools.extractHyperstack(server, request);
		return toTiffBytes(imp);
	}

	public static String getTiffStackBase64(ImageServer<BufferedImage> server, double downsample) throws IOException {
		return base64Encode(getTiffStack(server, downsample));
	}

	public static String getTiffStackBase64(ImageServer<BufferedImage> server, double downsample, int x, int y, int width, int height) throws IOException {
		return base64Encode(getTiffStack(server, downsample, x, y, width, height));
	}

	public static String getTiffStackBase64(ImageServer<BufferedImage> server, double downsample, int x, int y, int width, int height, int z, int t) throws IOException {
		return base64Encode(getTiffStack(server, downsample, x, y, width, height, z, t));
	}

	public static String getTiffStackBase64(ImageServer<BufferedImage> server, RegionRequest request) throws IOException {
		return base64Encode(getTiffStack(server, request));
	}
	
	public static byte[] getImageBytes(ImageServer<BufferedImage> server, double downsample, String format) throws IOException {
		return getImageBytes(server, downsample, 0, 0, server.getWidth(), server.getHeight(), format);
	}

	public static byte[] getImageBytes(ImageServer<BufferedImage> server, double downsample, int x, int y, int width, int height, String format) throws IOException {
		return getImageBytes(server, downsample, x, y, width, height, 0, 0, format);		
	}

	public static byte[] getImageBytes(ImageServer<BufferedImage> server, double downsample, int x, int y, int width, int height, int z, int t, String format) throws IOException {
		var request = RegionRequest.createInstance(server.getPath(), 
				downsample, x, y, width, height, z, t);
		byte[] result = getImageBytes(server, request, format);
		return result;
	}

	public static String getImageBase64(ImageServer<BufferedImage> server, double downsample, String format) throws IOException {
		return base64Encode(getImageBytes(server, downsample, format));
	}

	public static String getImageBase64(ImageServer<BufferedImage> server, double downsample, int x, int y, int width, int height, String format) throws IOException {
		return base64Encode(getImageBytes(server, downsample, x, y, width, height, format));
	}

	public static String getImageBase64(ImageServer<BufferedImage> server, double downsample, int x, int y, int width, int height, int z, int t, String format) throws IOException {
		return base64Encode(getImageBytes(server, downsample, x, y, width, height, z, t, format));
	}

	public static String getImageBase64(ImageServer<BufferedImage> server, RegionRequest request, String format) throws IOException {
		return base64Encode(getImageBytes(server, request, format));
	}

	public static byte[] getImageBytes(ImageServer<BufferedImage> server, RegionRequest request, String format) throws IOException {
		if (isImageJFormat(format)) {
			var imp = IJTools.convertToImagePlus(server, request).getImage();
			return toTiffBytes(imp);
		}
		var img = server.readRegion(request);
		return getImageBytes(img, format);
	}

	private static String base64Encode(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	private static boolean isImageJFormat(String format) {
		var fmt = format.toLowerCase();
		return Set.of("imagej tiff", "imagej tif").contains(fmt);
	}
		
	
	public static byte[] getImageBytes(BufferedImage img, String format) throws IOException {
		
		var fmt = format.toLowerCase();
		if (Set.of("imagej tiff", "imagej tif").contains(fmt)) {
			var imp = IJTools.convertToUncalibratedImagePlus("Image", img);
			return toTiffBytes(imp);
		}
		
		try (var stream = new ByteArrayOutputStream(Math.min(1024*1024*10, img.getWidth() * img.getHeight() + 1024))) {
			ImageIO.write(img, format, stream);
			var array = stream.toByteArray();
			return array;
		}
		
	}
	
	
	private static byte[] toTiffBytes(ImagePlus imp) {
		return new FileSaver(imp).serialize();
	}
	
	
//	public static ImageBytesServer wrap(ImageServer<BufferedImage> server) {
//		return new ImageBytesServer(server);
//	}
//	
//	
//	static class ImageBytesServer extends AbstractImageServer<byte[]> {
//
//		private String format;
//		private ImageServer<BufferedImage> baseServer;
//		
//		protected ImageBytesServer(ImageServer<BufferedImage> baseServer) {
//			super(byte[].class);
//			this.baseServer = baseServer;
//			if (this.baseServer.isRGB())
//				format = "png";
//			else
//				format = "imagej tiff";
//		}
//
//		@Override
//		public Collection<URI> getURIs() {
//			return baseServer.getURIs();
//		}
//
//		@Override
//		public String getServerType() {
//			return "TIFF bytes server (" + baseServer.getServerType() + ")";
//		}
//
//		@Override
//		public ImageServerMetadata getOriginalMetadata() {
//			return baseServer.getOriginalMetadata();
//		}
//
//		@Override
//		protected ServerBuilder<byte[]> createServerBuilder() {
//			throw new UnsupportedOperationException("Unable to create a ServerBuilder for TiffBytesServer");
//		}
//
//		@Override
//		protected String createID() {
//			return UUID.randomUUID().toString();
//		}
//		
//		@Override
//		public byte[] readRegion(double downsample, int x, int y, int width, int height, int z, int t) throws IOException {
//			return getImageBytes(baseServer, downsample, x, y, width, height, z, t, format);
//		}
//		
//		public Iterator<?> getTiles() {
//			return getTiles(0);
//		}
//		
//		public Iterator<?> getTiles(int level) {
//			return new ArrayList<>(baseServer.getTileRequestManager().getTileRequestsForLevel(level))
//				.stream()
//				.map(t -> {
//					try {
//						return readRegion(t.getRegionRequest());
//					} catch (IOException e) {
//						return e;
//					}
//				})
//				.iterator();
//		}
//		
//		public String getMetadataJson() {
//			return GsonTools.getInstance(true).toJson(baseServer.getMetadata());
//		}
//		
//		public String getPixelCalibrationJson() {
//			return GsonTools.getInstance(true).toJson(baseServer.getPixelCalibration());
//		}
//		
//	}


	static class Py4JImagePlaneTypeAdapter extends TypeAdapter<ImagePlane> {

		static Py4JImagePlaneTypeAdapter INSTANCE = new Py4JImagePlaneTypeAdapter();

		@Override
		public void write(JsonWriter out, ImagePlane plane) throws IOException {
			out.beginObject();
			out.name("c");
			out.value(plane.getC());
			out.name("z");
			out.value(plane.getZ());
			out.name("t");
			out.value(plane.getT());
			out.endObject();
		}

		@Override
		public ImagePlane read(JsonReader in) throws IOException {
			boolean isObject = in.peek() == JsonToken.BEGIN_OBJECT;

			if (isObject)
				in.beginObject();

			ImagePlane plane = ImagePlane.getDefaultPlane();
			int c = plane.getC();
			int z = plane.getZ();
			int t = plane.getT();

			while (in.hasNext()) {
				switch (in.nextName()) {
					case "c":
						c = in.nextInt();
						break;
					case "z":
						z = in.nextInt();
						break;
					case "t":
						t = in.nextInt();
						break;
				}
			}
			if (isObject)
				in.endObject();
			return ImagePlane.getPlaneWithChannel(c, z, t);
		}

	}

}
