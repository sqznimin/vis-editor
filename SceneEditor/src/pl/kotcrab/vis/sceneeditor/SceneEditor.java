/*******************************************************************************
 * Copyright 2014 Pawel Pastuszak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package pl.kotcrab.vis.sceneeditor;

import com.badlogic.gdx.Application.ApplicationType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ObjectMap.Entry;

/** Main class of VisSceneEditor
 * 
 * @author Pawel Pastuszak */
@SuppressWarnings({"rawtypes"})
// yeah, you know there are just warnings...
public class SceneEditor extends SceneEditorInputAdapater {
	private static final String TAG = "VisSceneEditor";

	private CameraController camController;

	private ObjectMap<Class<?>, SceneEditorSupport<?>> supportMap;
	private ObjectMap<String, Object> objectMap;

	private Renderer renderer;
	private Serializer serializer;
	private KeyboardInputMode keyboardInputMode;

	private boolean devMode;
	private boolean editing;
	private boolean dirty;
	private boolean cameraLocked;

	private Array<ObjectRepresentation> selectedObjs;
	private Array<ObjectRepresentation> objectRepresenationList;

	private Array<Array<EditorAction>> undoList;
	private Array<Array<EditorAction>> redoList;

	/** @see SceneEditor#SceneEditor(FileHandle, OrthographicCamera, boolean)
	 * @param registerBasicsSupports if true Sprite and Actor support will be registered */
	public SceneEditor (FileHandle sceneFile, OrthographicCamera camera, boolean devMode, boolean registerBasicsSupports) {
		this.devMode = devMode;

		// DevMode can be only activated on desktop
		if (Gdx.app.getType() != ApplicationType.Desktop) this.devMode = false;

		supportMap = new ObjectMap<>();
		objectMap = new ObjectMap<>();

		serializer = new Serializer(this, sceneFile, objectMap);

		if (registerBasicsSupports) {
			registerSupport(Sprite.class, new SpriteSupport());
			registerSupport(Actor.class, new ActorSupport());
		}

		if (devMode) {
			undoList = new Array<Array<EditorAction>>();
			redoList = new Array<Array<EditorAction>>();
			objectRepresenationList = new Array<ObjectRepresentation>();
			selectedObjs = new Array<ObjectRepresentation>();

			camController = new CameraController(camera);

			keyboardInputMode = new KeyboardInputMode(new KeyboardInputActionFinished() {
				@Override
				public void editingFinished (Array<EditorAction> actions) {
					undoList.add(actions);
				}
			}, selectedObjs);

			renderer = new Renderer(this, camController, keyboardInputMode, objectMap, selectedObjs);

			attachInputProcessor();
		}
	}

	/** Constructs SceneEditor with basic supports for Sprite and Actor
	 * 
	 * @param sceneFile path to scene file, typicaly with .json extension
	 * @param camera camera used for rendering
	 * @param devMode devMode allow to enter editing mode, if not on desktop it will automaticly be set to false */
	public SceneEditor (FileHandle sceneFile, OrthographicCamera camera, boolean devMode) {
		this(sceneFile, camera, devMode, true);
	}

	public void load () {
		serializer.load();
	}

	private void save () {
		if (serializer.save()) dirty = false;

	}

	/** Add obj to object list, if support for this object class was not registed it won't be added
	 * @param obj object that will be added to list
	 * @param identifier unique identifer, used when saving and loading
	 * 
	 * @return This SceneEditor for the purpose of chaining methods together. */
	public SceneEditor add (Object obj, String identifier) {
		if (isSupportForClassAvaiable(obj.getClass())) {
			objectMap.put(identifier, obj);
			objectRepresenationList.add(new ObjectRepresentation(getSupportForObject(obj), obj));
		}

		return this;
	}

	/** Register support and allow object of provied class be added to scene */
	public void registerSupport (Class<?> klass, SceneEditorSupport<?> support) {
		supportMap.put(klass, support);
	}

	/** Check if support for provied class is available
	 * @param klass class that will be checked
	 * @return true if support is avaiable. false otherwise */
	public boolean isSupportForClassAvaiable (Class klass) {
		if (supportMap.containsKey(klass))
			return true;
		else {
			if (klass.getSuperclass() == null)
				return false;
			else
				return isSupportForClassAvaiable(klass.getSuperclass());
		}
	}

	/** Returns support for provided class
	 * 
	 * @param klass class that support will be return if available
	 * @return support if available, null otherwise */
	public SceneEditorSupport getSupportForClass (Class klass) {
		if (supportMap.containsKey(klass))
			return supportMap.get(klass);
		else {
			if (klass.getSuperclass() == null)
				return null;
			else
				return getSupportForClass(klass.getSuperclass());
		}
	}

	public SceneEditorSupport getSupportForObject (Object obj) {
		return getSupportForClass(obj.getClass());
	}

	/** @param x pointer cordinate unprocjeted by camera
	 * @param y pointer cordinate unprocjeted by camera */
	private void setValuesForSelectedObject (float x, float y) {
		for (ObjectRepresentation orep : selectedObjs)
			orep.setValues(x, y);
	}

	/** Finds and return identifer for provied object
	 * 
	 * @param obj that identifier will be returned
	 * @return identifier if found, null otherwise */
	public String getIdentifierForObject (Object obj) {
		for (Entry<String, Object> entry : objectMap.entries()) {
			if (entry.value.equals(obj)) return entry.key;
		}

		return null;
	}

	/** Finds object with smallest surface area that contains x,y point
	 * @param x pointer cordinate unprocjeted by camera
	 * @param y pointer cordinate unprocjeted by camera
	 * @return */
	private ObjectRepresentation findObjectWithSamllestSurfaceArea (float x, float y) {
		ObjectRepresentation matchingObject = null;
		int lastSurfaceArea = Integer.MAX_VALUE;

		for (ObjectRepresentation orep : objectRepresenationList) {
			if (orep.contains(x, y)) {
				int currentSurfaceArea = (int)(orep.getWidth() * orep.getHeight());

				if (currentSurfaceArea < lastSurfaceArea) {
					matchingObject = orep;
					lastSurfaceArea = currentSurfaceArea;
				}
			}
		}

		return matchingObject;
	}

	/** Renders everything */
	public void render () {
		if (editing) {
			renderer.render(cameraLocked);
			renderer.renderGUI(objectMap.size, cameraLocked, dirty); // TODO render obj info
		}
	}

	private void undo () {
		if (undoList.size > 0) {
			Array<EditorAction> actions = undoList.pop();

			for (EditorAction action : actions)
				action.switchValues();

			redoList.add(actions);
		} else
			Gdx.app.log(TAG, "Can't undo any more!");
	}

	private void redo () {
		if (redoList.size > 0) {
			Array<EditorAction> actions = redoList.pop();

			for (EditorAction action : actions)
				action.switchValues();

			undoList.add(actions);
		} else
			Gdx.app.log(TAG, "Can't redo any more!");
	}

	private void addUndoActions () {
		Array<EditorAction> localUndoList = new Array<EditorAction>();

		for (ObjectRepresentation orep : selectedObjs)
			localUndoList.add(orep.getLastEditorAction());

		undoList.add(localUndoList);
	}

	private boolean areAllSelectedObjectSupportsMoving () {
		for (ObjectRepresentation orep : selectedObjs) {
			if (orep.isMovingSupported() == false) {
				Gdx.app.log(TAG, "Some of the selected object does not support moving.");
				return false;
			}
		}
		return true;
	}

	private boolean areAllSelectedObjectSupportsScalling () {
		for (ObjectRepresentation orep : selectedObjs) {
			if (orep.isScallingSupported() == false) {
				Gdx.app.log(TAG, "Some of the selected object does not support scalling.");
				return false;
			}
		}
		return true;
	}

	private boolean areAllSelectedObjectSupportsRotating () {
		for (ObjectRepresentation orep : selectedObjs) {
			if (orep.isRotatingSupported() == false) {
				Gdx.app.log(TAG, "Some of the selected object does not support rotating.");
				return false;
			}
		}
		return true;
	}

	/** Releases used assets */
	public void dispose () {
		if (devMode) {
			renderer.dispose();
		}
	}

	/** This must be called when screen size changed */
	public void resize () {
		if (devMode) renderer.resize();
	}

	/** Enabled editing mode */
	public void enable () {
		if (devMode) {
			if (editing == false) {
				editing = true;
				camController.switchCameraProperties();
			}
		}
	}

	/** Disabled editing mode */
	public void disable () {
		if (devMode) {
			if (editing) {
				editing = false;
				keyboardInputMode.cancel();
				camController.switchCameraProperties();
				save();
			}
		}
	}

	public boolean isDevMode () {
		return devMode;
	}

	/** {@inheritDoc} */
	@Override
	public void attachInputProcessor () {
		if (devMode) super.attachInputProcessor();
	}

	@Override
	public boolean keyDown (int keycode) {
		if (editing) {
			if (keyboardInputMode.isActive() == false) {
				if (keycode == SceneEditorConfig.KEY_RESET_CAMERA) camController.restoreOrginalCameraProperties();
				if (keycode == SceneEditorConfig.KEY_LOCK_CAMERA) cameraLocked = !cameraLocked;

				if (Gdx.input.isKeyPressed(SceneEditorConfig.KEY_SPECIAL_ACTIONS)) {
					if (keycode == SceneEditorConfig.KEY_SPECIAL_SAVE_CHANGES) save();
					if (keycode == SceneEditorConfig.KEY_SPECIAL_UNDO) undo();
					if (keycode == SceneEditorConfig.KEY_SPECIAL_REDO) redo();
					return true; // we don't want to trigger diffrent events
				}

				if (selectedObjs.size > 0) {

					if (areAllSelectedObjectSupportsMoving()) {
						if (keycode == SceneEditorConfig.KEY_INPUT_MODE_EDIT_POSX) keyboardInputMode.setObject(EditType.X);
						if (keycode == SceneEditorConfig.KEY_INPUT_MODE_EDIT_POSY) keyboardInputMode.setObject(EditType.Y);
					}

					if (areAllSelectedObjectSupportsScalling()) {
						if (keycode == SceneEditorConfig.KEY_INPUT_MODE_EDIT_WIDTH) keyboardInputMode.setObject(EditType.WIDTH);
						if (keycode == SceneEditorConfig.KEY_INPUT_MODE_EDIT_HEIGHT) keyboardInputMode.setObject(EditType.HEIGHT);
					}

					if (areAllSelectedObjectSupportsRotating()) {
						if (keycode == SceneEditorConfig.KEY_INPUT_MODE_EDIT_ROTATION) keyboardInputMode.setObject(EditType.ROTATION);
					}
				}
			}

			keyboardInputMode.keyDown(keycode);
		}

		if (keycode == SceneEditorConfig.KEY_TOGGLE_EDIT_MODE) {
			if (editing)
				disable();
			else
				enable();

			return true;
		}

		return false;
	}

	@Override
	public boolean touchDown (int screenX, int screenY, int pointer, int button) {

		if (editing) {
			keyboardInputMode.finish();

			final float x = camController.calcX(screenX);
			final float y = camController.calcY(screenY);

			if (Gdx.input.isKeyPressed(SceneEditorConfig.KEY_NO_SELECT_MODE))
				selectedObjs.clear();
			else {
				if (isMouseInsideSelectedObjectAreas() == false) {
					ObjectRepresentation matchingObject = findObjectWithSamllestSurfaceArea(x, y);

					if (matchingObject != null) {
						selectedObjs.clear();
						selectedObjs.add(matchingObject);

						setValuesForSelectedObject(x, y);

						return true;
					}

					selectedObjs.clear();
				} else {
					setValuesForSelectedObject(x, y);
					return true;
				}
			}
		}

		return false;
	}

	private boolean isMouseInsideSelectedObjectAreas () {
		for (ObjectRepresentation orep : selectedObjs) {
			if (orep.isPointerInsideRotateArea() || orep.isPointerInsideScaleArea()) return true;
		}
		return false;
	}

	@Override
	public boolean touchUp (int screenX, int screenY, int pointer, int button) {

		if (editing) {
			keyboardInputMode.finish();

			if (selectedObjs.size > 0) addUndoActions();
		}

		return false;
	}

	@Override
	public boolean mouseMoved (int screenX, int screenY) {
		if (editing) {
			float x = camController.calcX(screenX);
			float y = camController.calcY(screenY);

			for (ObjectRepresentation orep : selectedObjs)
				orep.mouseMoved(x, y);
		}

		return false;
	}

	@Override
	public boolean touchDragged (int screenX, int screenY, int pointer) {
		final float x = camController.calcX(screenX);
		final float y = camController.calcY(screenY);

		if (editing) {
			keyboardInputMode.finish();

			// sorry...
			if (Gdx.input.isButtonPressed(Buttons.LEFT)) {
				for (ObjectRepresentation orep : selectedObjs) {
					if (orep.isPointerInsideRotateArea()) {
						if (orep.draggedRotate(x, y)) dirty = true;

					} else if (orep.isPointerInsideScaleArea()) {
						if (orep.draggedScale(x, y)) dirty = true;

					} else if (orep.draggedMove(x, y)) dirty = true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean scrolled (int amount) {
		if (editing && cameraLocked == false) return camController.scrolled(amount);

		return false;
	}

	// pan is worse because you must drag mouse a little bit to fire this event, but it's simpler
	@Override
	public boolean pan (float x, float y, float deltaX, float deltaY) {
		if (editing) {
			keyboardInputMode.finish();

			if (Gdx.input.isButtonPressed(Buttons.LEFT)) {
				if (selectedObjs.size == 0 && cameraLocked == false) {
					return camController.pan(deltaX, deltaY);
				}
			}
		}

		return false;
	}

}
