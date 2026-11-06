// One generation covers source resolution, cache reads, prepare and retry.
export function createPlaybackIntent() {
    let generation = 0;
    return {
        begin: () => ++generation,
        cancel: () => { generation += 1; },
        current: () => generation,
        isCurrent: token => token === generation
    };
}
