/**
 * 当前是否处于离线状态。
 * navigator.onLine === false 是「确定没网」，此时任何请求失败都必然是网络问题。
 */
export const isOffline = () => typeof navigator !== 'undefined' && navigator.onLine === false;

/**
 * 判断一个错误是否为网络类失败（断网、超时、连接被拒、DNS 失败）。
 *
 * 这类失败的特征是「请求发出去了，但没拿到任何 HTTP 响应」——
 * 重试有用，必须和「服务端明确说这首歌播不了」区分开。
 * 混为一谈会导致断网时被当成版权问题去切歌，最后弹「请手动选择歌曲」。
 */
export const isNetworkFailure = (error) => {
    // 已经确定没网，不必再看错误长什么样
    if (isOffline()) return true;

    if (!error) return false;

    const code = error.code || '';
    if (
        code === 'ECONNABORTED' ||
        code === 'ERR_NETWORK' ||
        code === 'ETIMEDOUT' ||
        code === 'ERR_INTERNET_DISCONNECTED' ||
        code === 'ERR_CONNECTION_REFUSED'
    ) {
        return true;
    }

    // axios：有 request 但没有 response，说明连接层面就没通
    if (error.request && !error.response) return true;

    // 兜底：浏览器 / WebView 抛的裸异常（如 TypeError: Failed to fetch）
    if (!error.request && !error.response && /network|timeout|failed to fetch/i.test(error.message || '')) {
        return true;
    }

    return false;
};

export default isNetworkFailure;
