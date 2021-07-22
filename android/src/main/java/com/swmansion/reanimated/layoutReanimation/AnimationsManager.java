package com.swmansion.reanimated.layoutReanimation;

import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.facebook.react.bridge.JavaOnlyMap;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.IViewManagerWithChildren;
import com.facebook.react.uimanager.IllegalViewOperationException;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.ReactStylesDiffMap;
import com.facebook.react.uimanager.RootView;
import com.facebook.react.uimanager.UIImplementation;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.ViewManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class AnimationsManager implements ViewHierarchyObserver{
    private final static String[] LAYOUT_KEYS = { Snapshooter.originX, Snapshooter.originY, Snapshooter.width, Snapshooter.height };
    private ReactContext mContext;
    private UIImplementation mUIImplementation;
    private UIManagerModule mUIManager;
    private NativeMethodsHolder mNativeMethodsHolder;

    private HashMap<Integer, ViewState> mStates;
    private HashMap<Integer, View> mViewForTag;
    private HashSet<Integer> mToRemove;
    private HashMap<Integer, ViewManager> mViewManager;
    private HashMap<Integer, ViewManager> mParentViewManager;
    private HashMap<Integer, View> mParent;

    public enum ViewState {
        Appearing, Disappearing, Layout, Inactive, ToRemove;
    }

    AnimationsManager(ReactContext context, UIImplementation uiImplementation, UIManagerModule uiManagerModule) {
        mContext = context;
        mUIImplementation = uiImplementation;
        mUIManager = uiManagerModule;
        mStates = new HashMap<>();
        mViewForTag = new HashMap<>();
        mToRemove = new HashSet<>();
        mViewManager = new HashMap<>();
        mParentViewManager = new HashMap<>();
        mParent = new HashMap();
    }

    public void onCatalystInstanceDestroy() {
        mNativeMethodsHolder = null;
        mContext = null;
        mUIImplementation = null;
        mUIManager = null;
        mStates = null;
        mToRemove = null;
        mViewForTag = null;
        mViewManager = null;
        mParent = null;
        mParentViewManager = null;
    }

    @Override
    public void onViewRemoval(View view, ViewGroup parent, Snapshot before) {
        Integer tag = view.getId();
        String type = "entering";
        HashMap<String, Object> startValues = before.toMap();
        ViewState state = mStates.get(view.getId());
        ViewTraverser.attach(view, parent, null, startValues);

        if (state == ViewState.Disappearing || state == ViewState.ToRemove) {
            return;
        }
        if (state == ViewState.Appearing) {
            mStates.put(tag, ViewState.Disappearing);
            type = "exiting";
            HashMap<String, Float> preparedValues = prepareDataForAnimationWorklet(startValues);
            mNativeMethodsHolder.startAnimationForTag(tag, type, preparedValues);
            return;
        }
        if (state == ViewState.Inactive) {
            if (startValues != null) {
                mStates.put(view.getId(), ViewState.ToRemove);
                scheduleCleaning();
            }
            return;
        }
        mStates.put(tag, ViewState.Disappearing);
        type = "exiting";
        HashMap<String, Float> preparedValues = prepareDataForAnimationWorklet(startValues);
        mNativeMethodsHolder.startAnimationForTag(tag, type, preparedValues);
    }

    @Override
    public void onViewCreate(View view, ViewGroup parent, Snapshot after) {
        if (!mStates.containsKey(view.getId())) {
            mStates.put(view.getId(), ViewState.Inactive);
            mViewForTag.put(view.getId(), view);
            HashMap<String, Object> data = after.toMap();
            mViewManager.put(view.getId(), (ViewManager)data.get(Snapshooter.viewManager));
            mParentViewManager.put(view.getId(), (ViewManager)data.get(Snapshooter.parentViewManager));
            mParent.put(view.getId(), ((View)data.get(Snapshooter.parent)));
        }
        Integer tag = view.getId();
        String type = "entering";
        HashMap<String, Object> targetValues = after.toMap();
        ViewState state = mStates.get(view.getId());

        if (state == ViewState.Disappearing || state == ViewState.ToRemove) {
            return;
        }

        if (state == ViewState.Inactive) { // it can be a fresh view
            if (targetValues != null) {
                HashMap<String, Float> preparedValues = prepareDataForAnimationWorklet(targetValues);
                mNativeMethodsHolder.startAnimationForTag(tag, type, preparedValues);
            }
            return;
        }
    }

    @Override
    public void onViewUpdate(View view, Snapshot before, Snapshot after) {
        Integer tag = view.getId();
        String type = "entering";
        HashMap<String, Object> targetValues = after.toMap();
        HashMap<String, Object> startValues = before.toMap();
        ViewState state = mStates.get(view.getId());
        // If startValues are equal to targetValues it means that there was no UI Operation changing
        // layout of the View. So dirtiness of that View is false positive
        if (state == ViewState.Appearing) {
            boolean doNotStartLayout = true;
            for (String key : LAYOUT_KEYS) {
                double startV = ((Number) startValues.get(key)).doubleValue();
                double targetV = ((Number) targetValues.get(key)).doubleValue();
                if (startV != targetV) {
                    doNotStartLayout = false;
                }
            }
            if (doNotStartLayout) {
                return;
            }
        }

        // View must be in Layout state
        type = "layout";
        if (startValues != null && targetValues == null) {
            mStates.put(view.getId(), ViewState.Disappearing);
            type = "exiting";
            HashMap<String, Float> preparedValues = prepareDataForAnimationWorklet(startValues);
            mNativeMethodsHolder.startAnimationForTag(tag, type, preparedValues);
            return;
        }
        HashMap<String, Float> preparedStartValues = prepareDataForAnimationWorklet(startValues);
        HashMap<String, Float> preparedTargetValues = prepareDataForAnimationWorklet(targetValues);
        HashMap<String, Float> preparedValues = new HashMap<>(preparedTargetValues);
        for (String key : preparedStartValues.keySet()) {
            preparedValues.put("b" + key, preparedStartValues.get(key));
        }
        mNativeMethodsHolder.startAnimationForTag(tag, type, preparedValues);
    }

    public void notifyAboutProgress(Map<String, Object> newStyle, Integer tag) {
       ViewState state = mStates.get(tag);
       if (state == ViewState.Inactive) {
           mStates.put(tag, ViewState.Appearing);
       }

       setNewProps(newStyle, mViewForTag.get(tag), mViewManager.get(tag), mParentViewManager.get(tag), mParent.get(tag).getId());
    }

    public void notifyAboutEnd(int tag, boolean cancelled) {

        if (!cancelled) {
            ViewState state = mStates.get(tag);
            if (state == ViewState.Appearing) {
                mStates.put(tag, ViewState.Layout);
            }

            if (state == ViewState.Disappearing) {
                mStates.put(tag, ViewState.ToRemove);
                mToRemove.add(tag);
                scheduleCleaning();
            }
        }
    }

    private void scheduleCleaning() {
        WeakReference<AnimationsManager> animationsManagerWeakReference = new WeakReference<>(this);
        mContext.runOnUiQueueThread(() -> {
            AnimationsManager thiz = animationsManagerWeakReference.get();
            if (thiz == null) {
                return;
            }
            HashSet<Integer> toRemove = mToRemove;
            mToRemove = new HashSet<>();
            ArrayList<View> toRemoveFromBottomToTop = new ArrayList<>();
            int [] intHolder = new int[1];
            intHolder[0] = 0;
            // go through ready to remove from bottom to top
            for (int tag: toRemove) {
                View view = mViewForTag.get(tag);
                dfs(view, intHolder, toRemoveFromBottomToTop, toRemove, );
            }

            for (View view : toRemoveFromBottomToTop) {
                if (!(view instanceof ViewGroup) || ((((ViewGroup) view).getChildCount() == 0))) {
                    if (view.getParent() != null) {
                        ViewGroup parent = (ViewGroup) view.getParent();
                        parent.removeView(view);
                    }
                }
                View curView = view;
                mStates.remove(curView.getId());
                mViewForTag.remove(curView.getId());
                mViewManager.remove(curView.getId());
                mParentViewManager.remove(curView.getId());
                mParent.remove(curView.getId());
                mNativeMethodsHolder.removeConfigForTag(curView.getId());
            }

        });
    }

    private void dfs(View view, int[] intHolder, ArrayList<View> toRemoveFromBottomToTop, HashSet<Integer> cands) {

    }

    public HashMap<String, Float> prepareDataForAnimationWorklet(HashMap<String, Object> values) {
        HashMap<String, Float> preparedValues = new HashMap<>();

        ArrayList<String> keys = new ArrayList<String>(Arrays.asList(Snapshooter.width, Snapshooter.height, Snapshooter.originX,
                Snapshooter.originY, Snapshooter.globalOriginX, Snapshooter.globalOriginY));
        for (String key : keys) {
            preparedValues.put(key, PixelUtil.toDIPFromPixel((int)values.get(key)));
        }

        DisplayMetrics displaymetrics = new DisplayMetrics();
        mContext.getCurrentActivity().getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
        int height = displaymetrics.heightPixels;
        int width = displaymetrics.widthPixels;
        preparedValues.put("windowWidth", PixelUtil.toDIPFromPixel(width));
        preparedValues.put("windowHeight", PixelUtil.toDIPFromPixel(height));
        return preparedValues;
    }

    public void setNativeMethods(NativeMethodsHolder nativeMethods) {
        mNativeMethodsHolder = nativeMethods;
    }

    public void setNewProps(Map<String, Object> props,
                            View view,
                            ViewManager viewManager,
                            ViewManager parentViewManager,
                            Integer parentTag) {
        float x = (props.get(Snapshooter.originX) != null)? ((Double)props.get(Snapshooter.originX)).floatValue() : PixelUtil.toDIPFromPixel(view.getLeft());
        float y = (props.get(Snapshooter.originY) != null)? ((Double)props.get(Snapshooter.originY)).floatValue() : PixelUtil.toDIPFromPixel(view.getTop());
        float width = (props.get(Snapshooter.width) != null)? ((Double)props.get(Snapshooter.width)).floatValue() : PixelUtil.toDIPFromPixel(view.getWidth());
        float height = (props.get(Snapshooter.height) != null)? ((Double)props.get(Snapshooter.height)).floatValue() : PixelUtil.toDIPFromPixel(view.getHeight());
        updateLayout(view, parentViewManager, parentTag, view.getId(), x, y, width, height);
        props.remove(Snapshooter.originX);
        props.remove(Snapshooter.originY);
        props.remove(Snapshooter.width);
        props.remove(Snapshooter.height);

        if (props.size() == 0) {
            return;
        }

        JavaOnlyMap javaOnlyMap = new JavaOnlyMap();
        for (String key : props.keySet()) {
            addProp(javaOnlyMap, key, props.get(key));
        }

        viewManager.updateProperties(view, new ReactStylesDiffMap(javaOnlyMap));
    }

    private static void addProp(WritableMap propMap, String key, Object value) {
        if (value == null) {
            propMap.putNull(key);
        } else if (value instanceof Double) {
            propMap.putDouble(key, (Double) value);
        } else if (value instanceof Integer) {
            propMap.putInt(key, (Integer) value);
        } else if (value instanceof Number) {
            propMap.putDouble(key, ((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            propMap.putBoolean(key, (Boolean) value);
        } else if (value instanceof String) {
            propMap.putString(key, (String) value);
        } else if (value instanceof ReadableArray) {
            propMap.putArray(key, (ReadableArray) value);
        } else if (value instanceof ReadableMap) {
            propMap.putMap(key, (ReadableMap) value);
        } else {
            throw new IllegalStateException("Unknown type of animated value [Layout Aniamtions]");
        }
    }

    public void updateLayout(View viewToUpdate, ViewManager parentViewManager,
            int parentTag, int tag, float xf, float yf, float widthf, float heightf) {

            int x = Math.round(PixelUtil.toPixelFromDIP(xf));
            int y = Math.round(PixelUtil.toPixelFromDIP(yf));
            int width = Math.round(PixelUtil.toPixelFromDIP(widthf));
            int height = Math.round(PixelUtil.toPixelFromDIP(heightf));
        // Even though we have exact dimensions, we still call measure because some platform views
        // (e.g.
        // Switch) assume that method will always be called before onLayout and onDraw. They use it to
        // calculate and cache information used in the draw pass. For most views, onMeasure can be
        // stubbed out to only call setMeasuredDimensions. For ViewGroups, onLayout should be stubbed
        // out to not recursively call layout on its children: React Native already handles doing
        // that.
        //
        // Also, note measure and layout need to be called *after* all View properties have been
        // updated
        // because of caching and calculation that may occur in onMeasure and onLayout. Layout
        // operations should also follow the native view hierarchy and go top to bottom for
        // consistency
        // with standard layout passes (some views may depend on this).

        viewToUpdate.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));

        // We update the layout of the ReactRootView when there is a change in the layout of its
        // child.
        // This is required to re-measure the size of the native View container (usually a
        // FrameLayout) that is configured with layout_height = WRAP_CONTENT or layout_width =
        // WRAP_CONTENT
        //
        // This code is going to be executed ONLY when there is a change in the size of the Root
        // View defined in the js side. Changes in the layout of inner views will not trigger an
        // update
        // on the layout of the Root View.
        ViewParent parent = viewToUpdate.getParent();
        if (parent instanceof RootView) {
            parent.requestLayout();
        }

        // Check if the parent of the view has to layout the view, or the child has to lay itself out.
        if (parentTag % 10 == 1) { // ParentIsARoot
            IViewManagerWithChildren parentViewManagerWithChildren;
            if (parentViewManager instanceof IViewManagerWithChildren) {
                parentViewManagerWithChildren = (IViewManagerWithChildren) parentViewManager;
            } else {
                throw new IllegalViewOperationException(
                        "Trying to use view with tag "
                                + parentTag
                                + " as a parent, but its Manager doesn't implement IViewManagerWithChildren");
            }
            if (parentViewManagerWithChildren != null
                    && !parentViewManagerWithChildren.needsCustomLayoutForChildren()) {
                viewToUpdate.layout(x, y, x + width, y + height);
            }
        } else {
            viewToUpdate.layout(x, y, x + width, y + height);
        }

    }
}
