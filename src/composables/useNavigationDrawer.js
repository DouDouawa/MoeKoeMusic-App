import { ref } from 'vue';

/**
 * 侧边导航抽屉的开关状态。
 *
 * HomeLayout 负责渲染抽屉，App.vue 里的返回手势也要读它（决定是拉出抽屉还是收进后台），
 * 两处不在同一条组件树上，所以放在模块作用域共享，而不是各持一份。
 */
const drawerOpen = ref(false);

export function useNavigationDrawer() {
    const open = () => { drawerOpen.value = true; };
    const close = () => { drawerOpen.value = false; };
    const toggle = () => { drawerOpen.value = !drawerOpen.value; };
    return { drawerOpen, open, close, toggle };
}
