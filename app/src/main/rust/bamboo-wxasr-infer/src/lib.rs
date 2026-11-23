//! WeChat offline ASR (xnet int8 streaming transducer) — JNI surface for BambooIME.
//!
//! Upstream: WowType `voice/src/wtlocal/` (see NOTICE.md). The model files themselves are
//! NOT distributed here: the user installs the provider's own pack, we only read it.
//!
//! Kotlin side: `com.osfans.trime.infer.WxAsrInfer` (loadLibrary "bamboo_wxasr_infer").

pub mod fbank;
pub mod kernels;
pub mod model;
pub mod parallel;
pub mod stream;
pub mod xnet;

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use anyhow::Result;
use jni::objects::{JClass, JPrimitiveArray, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

use model::Model;
use stream::Stream;

/// Parsed model, cached across sessions: parsing the 130 MB pack is the expensive part
/// (~140 ms warm, far more on a cold cache), while a fresh `Stream` is cheap. Keeping the
/// cache means every utterance can start from a clean stream — which is what prevents the
/// previous utterance's audio from bleeding into the next one.
static MODEL_CACHE: Mutex<Option<(PathBuf, Arc<Model>)>> = Mutex::new(None);

fn cached_model(dir: &Path) -> Result<Arc<Model>> {
    let mut cache = MODEL_CACHE.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((cached, model)) = cache.as_ref() {
        if cached == dir {
            return Ok(model.clone());
        }
    }
    let model = Arc::new(Model::load(dir)?);
    *cache = Some((dir.to_path_buf(), model.clone()));
    Ok(model)
}

/// Opaque handle shared with Kotlin: `Stream` + the model alive + decoding config.
struct Handle {
    stream: Stream,
    model: Arc<Model>,
}

fn fail(err: anyhow::Error) -> String {
    format!("{err:#}")
}

/// Load the model directory (must contain one `*.xnet` + `dict.decoder.utf8.txt`).
///
/// Returns 0 on failure, otherwise a handle. Loading takes 1-3 s for the 130 MB pack,
/// so the caller must run this off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_osfans_trime_infer_WxAsrInfer_nativeLoad(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
) -> jlong {
    let path: String = match env.get_string(&dir) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    match cached_model(Path::new(&path)) {
        Ok(m) => {
            let h = Box::new(Handle {
                stream: Stream::new(m.clone()),
                model: m,
            });
            Box::into_raw(h) as jlong
        }
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", fail(e));
            0
        }
    }
}

/// Rebuild the decoding stream for a new utterance (model stays cached).
///
/// Call this at the start of every hold session: `Stream` carries the feature cache, the
/// committed-block cursor and the stabilizer state, so reusing it makes `finish()` decode
/// everything spoken so far — including the previous sentence.
#[no_mangle]
pub extern "system" fn Java_com_osfans_trime_infer_WxAsrInfer_nativeReset(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jni::sys::jint {
    if handle == 0 {
        return 0;
    }
    let h = unsafe { &mut *(handle as *mut Handle) };
    h.stream = Stream::new(h.model.clone());
    1
}

/// Feed PCM (16 kHz, mono, i16) into the stream.
#[no_mangle]
pub extern "system" fn Java_com_osfans_trime_infer_WxAsrInfer_nativePush(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    pcm: jni::sys::jshortArray,
) {
    if handle == 0 || pcm.is_null() {
        return;
    }
    let h = unsafe { &mut *(handle as *mut Handle) };
    let arr = unsafe { JPrimitiveArray::from_raw(pcm) };
    let len = match env.get_array_length(&arr) {
        Ok(n) if n > 0 => n as usize,
        _ => return,
    };
    let mut buf = vec![0i16; len];
    if env.get_short_array_region(&arr, 0, &mut buf).is_err() {
        return;
    }
    h.stream.push(&buf);
}

/// Frames accumulated since the last partial() call (10 ms per frame).
#[no_mangle]
pub extern "system" fn Java_com_osfans_trime_infer_WxAsrInfer_nativeFresh(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jni::sys::jint {
    if handle == 0 {
        return 0;
    }
    let h = unsafe { &*(handle as *const Handle) };
    h.stream.fresh() as jni::sys::jint
}

/// Interim text (trailing punctuation trimmed, so "边说边打" does not flicker).
#[no_mangle]
pub extern "system" fn Java_com_osfans_trime_infer_WxAsrInfer_nativePartial(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let h = unsafe { &mut *(handle as *mut Handle) };
    let text = h.stream.partial();
    new_string(&env, &text)
}

/// Final text for the whole utterance.
#[no_mangle]
pub extern "system" fn Java_com_osfans_trime_infer_WxAsrInfer_nativeFinish(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let h = unsafe { &mut *(handle as *mut Handle) };
    let text = h.stream.finish();
    new_string(&env, &text)
}

/// Drop the handle: all buffers go away (the parsed model stays in the cache).
#[no_mangle]
pub extern "system" fn Java_com_osfans_trime_infer_WxAsrInfer_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    unsafe {
        drop(Box::from_raw(handle as *mut Handle));
    }
}

fn new_string(env: &JNIEnv, s: &str) -> jstring {
    // Text is plain UTF-8; fall back to an empty string rather than throwing in the
    // audio thread path.
    env.new_string(s)
        .map(|js| js.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[allow(dead_code)]
fn _assert_api(m: &Model) -> Result<()> {
    let ids = m.forward(&[]);
    let _ = m.text(&ids);
    Ok(())
}
