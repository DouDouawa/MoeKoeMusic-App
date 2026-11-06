<template>
    <div v-if="showModal" class="modal-overlay">
        <div class="modal-content user-agreement-guide">
            <img class="modal-banner" :src="DISCLAIMER_BANNER_SRC" alt="Banner">
            <h2>{{ $t('yong-hu-tiao-kuan') }}</h2>
            <p v-for="key in USER_AGREEMENT_KEYS" :key="key">{{ $t(key) }}</p>
            <div class="button-group">
                <button @click="agree">{{ $t('tong-yi') }}</button>
                <button @click="disagree">{{ $t('bu-tong-yi') }}</button>
            </div>
        </div>
    </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { DISCLAIMER_BANNER_SRC, USER_AGREEMENT_KEYS } from '../config/disclaimer';

const showModal = ref(false);

onMounted(() => {
    if(isElectron()){
        window.electron.ipcRenderer.on('show-disclaimer', () => {
            showModal.value = true;
        });
        return
    }
    if(!localStorage.getItem('disclaimerAccepted')){
        showModal.value = true;
    }
});
const isElectron = () => {
    return typeof window !== 'undefined' && typeof window.electron !== 'undefined';
};
const agree = () => {
    showModal.value = false;
    if(isElectron()){
        window.electron.ipcRenderer.send('disclaimer-response', true);
        return;
    }
    localStorage.setItem('disclaimerAccepted', true);
};

const disagree = () => {
    if(isElectron()){
        window.electron.ipcRenderer.send('disclaimer-response', false);
    }
    window.location.href = 'about:blank';
    window.close();
};
</script>

<style scoped>
.modal-overlay {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    padding: calc(12px + var(--app-safe-top, 0px)) 12px calc(12px + var(--app-safe-bottom, 0px));
    box-sizing: border-box;
    background: rgba(0, 0, 0, 0.6);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
    overflow-y: auto;
    overscroll-behavior: contain;
}

.modal-content {
    position: relative;
    background: #fff;
    color: #1f1f1f;
    padding: 20px;
    border-radius: 8px;
    max-width: 750px;
    width: 80%;
    max-height: calc(100dvh - 24px - var(--app-safe-top, 0px) - var(--app-safe-bottom, 0px));
    overflow-y: auto;
    box-sizing: border-box;
    overflow-wrap: anywhere;
}

.modal-banner {
    position: absolute;
    top: 0;
    right: 15px;
    width: 180px;
    max-width: 35%;
    pointer-events: none;
    user-select: none;
    -webkit-user-drag: none;
}

.button-group {
    display: flex;
    justify-content: space-around;
    margin-top: 15px;
}

button {
    padding: 10px 20px;
    border: none;
    cursor: pointer;
    border-radius: 5px;
    background-color:var(--primary-color);
    color: white;
}

/* 手机：宽度吃满可用区、正文可滚动、按钮 44px 命中且不被安全区压住 */
@media (max-width: 768px) {
    .modal-overlay {
        align-items: center;
        padding: 12px 10px;
    }

    .modal-content {
        width: 100%;
        max-width: 100%;
        padding: 16px;
        border-radius: 12px;
    }

    .modal-content h2 {
        margin-top: 0;
        font-size: 18px;
    }

    .modal-content p {
        font-size: 13px;
        line-height: 1.6;
        margin: 8px 0;
    }

    .modal-banner {
        width: 96px;
    }

    .button-group {
        position: sticky;
        bottom: 0;
        gap: 10px;
        padding-top: 10px;
        background: inherit;
    }

    .button-group button {
        flex: 1;
        min-height: 44px;
        font-size: 15px;
    }
}

@media (max-width: 900px) and (max-height: 480px) {
    .modal-content {
        max-height: calc(100dvh - 12px);
        padding: 12px;
    }

    .modal-banner { display: none; }
}

</style>
