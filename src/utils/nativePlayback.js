import { Capacitor, registerPlugin } from '@capacitor/core';
import { MoeAuthStore } from '../stores/store';
import { getApiBaseUrl } from './apiBaseUrl';

export const isNativePlayback = () => Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';
export const nativePlayback = registerPlugin('MoePlayback');

export function readPlayerSetting(key, fallback = null) {
    try {
        const value = JSON.parse(localStorage.getItem(key) || 'null');
        return value ?? fallback;
    } catch {
        return fallback;
    }
}

export function getNativePlaybackConfig() {
    const auth = MoeAuthStore();
    const user = auth.UserInfo || {};
    const device = auth.Device || {};
    const values = {
        token: user.token, userid: user.userid, t1: user.t1, dfid: device.dfid,
        KUGOU_API_MID: device.mid, KUGOU_API_GUID: device.guid,
        KUGOU_API_DEV: device.serverDev, KUGOU_API_MAC: device.mac
    };
    const settings = readPlayerSetting('settings', {});
    const limit = Number(settings.audioCacheLimitMB);
    return {
        apiBaseUrl: new URL(getApiBaseUrl(), window.location.href).href.replace(/\/$/, ''),
        authorization: Object.entries(values).filter(([, value]) => value).map(([key, value]) => `${key}=${value}`).join(';'),
        accountId: String(user.userid || ''),
        authenticated: !!auth.isAuthenticated,
        quality: settings.quality || '128',
        cacheEnabled: settings.audioCacheEnabled !== false && settings.audioCacheEnabled !== 'off',
        cacheLimitBytes: Math.floor((Number.isFinite(limit) && limit > 0 ? limit : 500) * 1024 * 1024)
    };
}

let configurationKey = '';
let configurationTask = Promise.resolve();
export function configureNativePlayback(force = false) {
    if (!isNativePlayback()) return Promise.resolve();
    const config = getNativePlaybackConfig();
    const key = JSON.stringify(config);
    if (!force && key === configurationKey) return configurationTask;
    configurationKey = key;
    configurationTask = configurationTask.catch(() => {}).then(() => nativePlayback.configure(config)).catch(error => {
        if (configurationKey === key) configurationKey = '';
        throw error;
    });
    return configurationTask;
}

// Never serialize Files, handles, proxies or ephemeral blob URLs across the native bridge.
export function toNativeSong(song = {}) {
    const result = {};
    for (const key of ['id', 'itemId', 'hash', 'playHash', 'name', 'author', 'album', 'timeLength', 'mixsongid',
        'requestedQuality', 'resolvedQuality', 'qualityLabel', 'isLocal', 'isCloud', 'isPreview', 'cacheKey', 'accountId']) {
        if (song[key] !== undefined && song[key] !== null) result[key] = song[key];
    }
    result.name ||= song.displayName || song.name || '';
    result.img = String(song.img || song.cover || '').replace('{size}', '480');
    if (result.img.startsWith('blob:')) result.img = '';
    result.timeLength ||= song.timelen || (Number(song.duration) * 1000) || 0;
    for (const key of ['uri', 'url']) {
        if (typeof song[key] === 'string' && !song[key].startsWith('blob:')) result[key] = song[key];
    }
    if (Array.isArray(song.qualityOptions)) {
        result.qualityOptions = song.qualityOptions.map(({ hash, value, label }) => ({ hash, value, label }));
    }
    return result;
}

export async function playNativeSong(song, options = {}) {
    await configureNativePlayback();
    return nativePlayback.play({ song: toNativeSong(song), ...options });
}

export async function setNativeQueue(songs, options = {}) {
    await configureNativePlayback();
    return nativePlayback.setQueue({ songs: songs.map(toNativeSong), ...options });
}
