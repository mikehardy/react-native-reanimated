/* global _stopObservingProgress, _startObservingProgress */
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-nocheck
import { Platform, requireNativeComponent, SliderComponent } from 'react-native';
import React from 'react';
import { runOnUI } from '../core';
import { withStyleAnimation } from '../animations';

runOnUI(() => {
  'worklet';

  const configs = {};

  global.LayoutAnimationRepository = {
    configs,
    registerConfig(tag, config) {
      configs[tag] = config;
    },
    removeConfig(tag) {
      delete configs[tag];
    },
    startAnimationForTag(tag, type, yogaValues) {
      if (configs[tag] == null) {
        return; // :(
      }

      if (typeof configs[tag][type] !== 'function') {
        console.error(`${type} animation for a tag: ${tag} it not a function!`);
      }

      const style = configs[tag][type](yogaValues);
      const sv = configs[tag].sv;
      _stopObservingProgress(tag, false);
      _startObservingProgress(tag, sv);
      sv._value = Object.assign({}, sv._value, style.initialValues);
      _stopObservingProgress(tag, false);
      const animation = withStyleAnimation(style.animations);

      animation.callback = (finished) => {
        if (finished) {
          _stopObservingProgress(tag, finished);
        }
      };
      configs[tag].sv.value = animation;
      _startObservingProgress(tag, sv);
    },
  };
})();
