package org.azavea.map;


import org.azavea.otm.App;
import org.azavea.otm.rest.handlers.TileHandler;
import org.azavea.otm.ui.MapDisplay;

import com.google.android.maps.GeoPoint;
import com.google.android.maps.MapView;
import com.google.android.maps.Projection;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceView;
import android.view.WindowManager;

// WMSTileRaster maintains a grid of tiles,
// the center of which will be in the center
// of the screen at time of initialization.
//
// As the user pans around, that center tile
// (and any surrounding tiles that should still
// be drawn) will be relocated in the grid, a
// different tile then becoming the center one.
//
// Naturally this process results in missing tiles
// in regions of the grid that now cover previously
// undrawn parts of the map. These tiles are replaced
// automatically.
public class WMSTileRaster extends SurfaceView {
	private Tile[][] tiles;
	private int numTilesX;
	private int numTilesY;
	private int tileWidth;
	private int tileHeight;
	private TileProvider tileProvider;
	private Paint paint;
	private MapDisplay activityMapDisplay;
	private GeoPoint topLeft;
	private GeoPoint bottomRight;
	private boolean initialized;
	private int initialTouchX;
	private int initialTouchY;
	private int panOffsetX;
	private int panOffsetY;
	private int initialTilesLoaded;
	
	private static final int BORDER_WIDTH = 2;

	private int initialZoomLevel;
	
	private boolean scaledTiles;
	
	private ZoomManager zoomManager;
	
	private int zoomTolerance;
	
	private boolean zoomComplete;
	
	private int pinchZoomOffsetX;
	private int pinchZoomOffsetY;
	
	public WMSTileRaster(Context context) throws Exception {
		super(context);
		init();
	}
	
	public WMSTileRaster(Context context, AttributeSet attrs) throws Exception {
		super(context, attrs);
		init();
	}
	
	public WMSTileRaster(Context context, AttributeSet attrs, int defStyle) throws Exception {
		super(context, attrs, defStyle);
		init();
	}
	
	// Associate this WMSTileRaster with a
	// MapView
	//
	// *** This should be handled in
	//     layout XML via a custom attribute
	//     but that approach is proving elusive
	//     at the moment ***
	public void setMapView(WindowManager windowManager, MapDisplay mapDisplay) {
		this.activityMapDisplay = mapDisplay;
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		super.onTouchEvent(event);

		if (initialized) {
			MapView mv = activityMapDisplay.getMapView();
			mv.onTouchEvent(event);
			mv.getZoomButtonsController().onTouch(mv, event);
		}

		return true; // Must be true or ACTION_MOVE isn't detected hence the
			     // need to manually pass the event to the MapView beforehand
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		
		if (activityMapDisplay != null) {
			if (!initialized) {
				int mapHeight = this.getHeight();
				int mapWidth = this.getWidth();
				tileHeight = mapHeight/(numTilesY - 2);
				tileWidth = mapWidth/(numTilesX - 2);
				
				zoomManager = new ZoomManager(tileWidth, tileHeight, initialZoomLevel);
				
				initialSetup();
			}

			int shuffleRight = determineShuffleRight();
			
			int shuffleDown = determineShuffleDown();
			
			if ((shuffleRight != 0 || shuffleDown != 0)) {
				synchronized (this) {
					relocateTiles(shuffleRight, shuffleDown);

					tileProvider.moveViewport(shuffleRight, shuffleDown);
					refreshTiles();
					
					updatePanOffsetX();

					updatePanOffsetY();
				}
			}
			
			if (initialized) {
				if (zoomManager.getZoomFactor() <= zoomTolerance && zoomManager.getZoomFactor() >= (1.0f/(float)zoomTolerance)) {
					drawTiles(canvas, panOffsetX, panOffsetY);
					zoomComplete = false;
				} else {
					if (zoomComplete) {
						forceReInit();
						zoomComplete = false;
					}
				}
			}
		}
	}

	public void forceReInit() {
		OTMMapView mv = activityMapDisplay.getMapView();

		initialZoomLevel = zoomManager.getZoomLevel();
		zoomManager.setInitialZoomLevel(initialZoomLevel);
		zoomManager.setZoomLevel(initialZoomLevel);
		Log.d("XDIFF", "zoomFactor " + zoomManager.getZoomFactor());

		// Reset pan offsets
		panOffsetX = 0;
		panOffsetY = 0;

		// Reload tiles using new viewport
		initializeTiles(mv);
		Log.d("MapViewChangeListener", "Reloaded tiles");
		this.postInvalidate();		
	}
	
	private void initializeTiles(MapView mv) {
		Projection proj = mv.getProjection();
		topLeft = proj.fromPixels(mv.getLeft(), mv.getTop() + tileHeight);
		bottomRight = proj.fromPixels(mv.getLeft() + tileWidth, mv.getTop());
		//tileProvider = new TileProvider(topLeft, bottomRight, numTilesX, numTilesY, tileWidth, tileHeight);
		loadTiles();
	}

	// Initialize this WMSTileRaster
	private void init() throws Exception {
		initialized = false;
		initialTilesLoaded = 0;
		initialTouchX = 0;
		initialTouchY = 0;
		panOffsetX = 0;
		panOffsetY = 0;
		initialZoomLevel = 14;
		scaledTiles = false;
		zoomTolerance = 2; // Allow one zoom-level before refreshing tiles from server
		zoomComplete = false;
		pinchZoomOffsetX = 0;
		pinchZoomOffsetY = 0;
		
		SharedPreferences prefs = App.getSharedPreferences();
		int numTilesWithoutBorderX = Integer.parseInt(prefs.getString("num_tiles_x", "0"));
		int numTilesWithoutBorderY = Integer.parseInt(prefs.getString("num_tiles_y", "0"));		

		if (numTilesWithoutBorderX == 0 || numTilesWithoutBorderY == 0) {
			throw new Exception("Invalid value(s) for num_tiles_x and/or num_tiles_y");
		}

		// Add border
		numTilesX = numTilesWithoutBorderX + BORDER_WIDTH;
		numTilesY = numTilesWithoutBorderY + BORDER_WIDTH;
		
		paint = new Paint();
		paint.setAlpha(0x888);
		
		setWillNotDraw(false);
	}

	// Initial load of all tiles in grid
	private void loadTiles() {
		// Start of this request group so increment the global sequence-id.
		App.incTileRequestSeqId();

		tileProvider = new TileProvider(topLeft, bottomRight, numTilesX, numTilesY, tileWidth, tileHeight);
		initialTilesLoaded = 0;

		tiles = new Tile[numTilesX][numTilesY];
		for(int x=0; x<numTilesX; x++) {
			tiles[x] = new Tile[numTilesY];
			for(int y=0; y<numTilesY; y++) {
				createTileRequest(x, y);
			}
		}
	}

	// Issue a request for a tile at screen
	// coordinates x, y and handle the resultant
	// response
	private void createTileRequest(int x, int y) {
		tileProvider.getTile(x-1, y-1, new TileHandler(x, y) {
			@Override
			public void tileImageReceived(int x, int y, Bitmap image) {
				Log.d("WMSTileRaster", "handler called");
				if (image != null) {
					Log.d("WMSTileRaster", "image available");
					tiles[x][y] = new Tile(image);

					// Remove from queue
					TileRequestQueue tileRequests = App.getTileRequestQueue();
					tileRequests.removeTileRequest(this.getBoundingBox());
					
					initialTilesLoaded++;
					if (initialTilesLoaded == 9) {
						activityMapDisplay.getMapView().invalidate();
					}
				}
			}
		});
	}

	// Draw all tiles in grid at specified
	// offset
	private void drawTiles(Canvas canvas, int offsetX, int offsetY) {
		canvas.save();
		Matrix m = canvas.getMatrix();
		m.preScale(zoomManager.getZoomFactor(), zoomManager.getZoomFactor());
		canvas.setMatrix(m);
		for(int x=0; x<numTilesX; x++) {
			for(int y=0; y<numTilesY; y++) {
				if (tiles[x][y] != null) {
					tiles[x][y].draw(canvas, (int)((x-1) * zoomManager.getWidth()), (int)((y-1) * -1 * zoomManager.getHeight()), tileWidth, tileHeight, offsetX, offsetY, zoomManager.getZoomFactor(), scaledTiles);
				}
			}
		}
		canvas.restore();
	}

	// Called when center bounding-box
	// is moved so that panOffsetY will
	// be made relative to new bounding-box
	private void updatePanOffsetY() {
		if (panOffsetY >= tileHeight) {
			panOffsetY = (int) (panOffsetY - tileHeight);
		}
		
		if (panOffsetY <= -tileHeight) {
			panOffsetY = (int) (panOffsetY + tileHeight);
		}
	}

	// Called when center bounding-box
	// is moved so that panOffsetX will
	// be made relative to new bounding-box
	private void updatePanOffsetX() {
		if (panOffsetX >= tileWidth) {
			panOffsetX = (int) (panOffsetX - tileWidth);
		}
		
		if (panOffsetX <= -tileWidth) {
			panOffsetX = (int) (panOffsetX + tileWidth);
		}
	}

	// Get vertical direction in which to
	// relocate tiles in grid
	private int determineShuffleDown() {
		int shuffleDown = 0;
		if (panOffsetY > zoomManager.getHeight()) {
			shuffleDown = 1;
		}
		
		if (panOffsetY < -zoomManager.getHeight()) {
			shuffleDown = -1;
		}
		return shuffleDown;
	}

	// Get horizontal direction in which to
	// relocate tiles in grid
	private int determineShuffleRight() {
		int shuffleRight = 0;
		if (panOffsetX > zoomManager.getWidth()) {
			shuffleRight = -1;
		}
		
		if (panOffsetX < -zoomManager.getWidth()) {
			shuffleRight = 1;
		}
		return shuffleRight;
	}

	// Initial bounding-box setup and
	// tile-load. This will result in
	// requests being generated for ALL
	// grid positions
	private void initialSetup() {
		if (activityMapDisplay != null ) {
			setupOnChangeListener();
			MapView mv = activityMapDisplay.getMapView();
			if (mv != null) {
				Projection proj = mv.getProjection();
				topLeft = proj.fromPixels(mv.getLeft(), mv.getTop() + tileHeight);
				bottomRight = proj.fromPixels(mv.getLeft() + tileWidth, mv.getTop());
				loadTiles();
				this.postInvalidate();
				initialized = true;
				initZoomPolling();
			} else {
				initialized = false;
			}
		}
	}
	
	private void setupOnChangeListener() {
		activityMapDisplay.getMapView().setOnChangeListener(new MapViewChangeListener());
	}
	
	private void initZoomPolling() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				while(true) {
					OTMMapView mv = activityMapDisplay.getMapView();
					
					// Find out how far map is from our viewport
					Projection proj = mv.getProjection();
					Point overlayTopLeft = new Point();
					proj.toPixels(topLeft, overlayTopLeft);

					panOffsetX = overlayTopLeft.x;
					panOffsetY = (int)(overlayTopLeft.y - (mv.getHeight() * zoomManager.getZoomFactor()));
				}
			}
		}).start();
	}

	// Move usable existing tiles within grid and
	// make space for new ones
	private void relocateTiles(int x, int y) {
            Tile[][] newTiles = new Tile[numTilesX][numTilesY];
            
            for(int k=0; k<numTilesX; k++) {
                    newTiles[k] = new Tile[numTilesY];
            }

            for(int i=0; i<numTilesX; i++) {
                    for(int j=0; j<numTilesY; j++) {
                            if (i+x<numTilesX && i+x>=0 && j+y<numTilesY && j+y>=0) {
                                    newTiles[i][j] = tiles[i+x][j+y];
                            }
                            else {
                                    newTiles[i][j] = null;
                            }
                    }
            }
            tiles = newTiles;
    }       
	
	// Load new tiles for spaces left in grid
	// by relocateTiles()
	private void refreshTiles() {
		// Set sequence-id for this request-group
		App.incTileRequestSeqId();
		
		for(int i=0; i<numTilesX; i++) {
			for(int j=0; j<numTilesY; j++) {
				if (tiles[i][j] == null) {
					tileProvider.getTile(i-1, j-1, new TileHandler(i, j) {
						@Override
						public void tileImageReceived(int x, int y, Bitmap image) {
							tiles[x][y] = new Tile(image);
							
							// Remove from queue
							TileRequestQueue tileRequests = App.getTileRequestQueue();
							tileRequests.removeTileRequest(this.getBoundingBox());

							activityMapDisplay.getMapView().invalidate();
						}
					});
				}
			}
		}
	}
	
	private class MapViewChangeListener implements OTMMapView.OnChangeListener {
		@Override
		public void onChange(MapView view, int newZoom, int oldZoom) {
			if (newZoom != oldZoom) {
				zoomManager.setZoomLevel(newZoom);

				zoomComplete = true;
				WMSTileRaster.this.invalidate();
			}
		}
	}
}
