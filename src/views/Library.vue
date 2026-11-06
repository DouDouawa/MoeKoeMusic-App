<template>
    <div class="library-page">
        <div class="profile-section">
            <div class="profile-header" :style="profileHeaderStyle">
                <div class="profile-background-image-wrap">
                    <div class="profile-background-image"></div>
                </div>
                <div class="profile-background-main"></div>
                <div class="profile-background-top"></div>
                <div class="profile-background-bottom"></div>
                <div class="profile-background-right"></div>
                <div class="profile-info">
                    <img class="profile-pic" :src="user.pic" :alt="$t('yong-hu-tou-xiang')" />
                    <div class="user-details">
                        <div class="user-name-row">
                            <h2 class="user-name">{{ user.nickname }}</h2>
                            <span class="user-level">Lv.{{ userDetail.p_grade || 0 }}</span>
                            <BirthdayEasterEgg :birthday="userDetail.birthday" :nickname="user.nickname"
                                :player-control="props.playerControl" />
                            <img v-if="userVip[0] && userVip[0].is_vip == 1" class="user-vip-icon"
                                :src="`./assets/images/${userVip[0].product_type === 'svip' ? 'vip' : 'vip2'}.png`"
                                :title="`${$t('gai-nian-ban')} ${userVip[0].vip_end_time}`" />
                            <img v-if="userVip[1] && userVip[1].is_vip == 1" class="user-vip-icon"
                                :src="`./assets/images/${userVip[1].product_type === 'svip' ? 'vip' : 'vip2'}.png`"
                                :title="`${$t('chang-ting-ban')} ${userVip[1].vip_end_time}`" />
                        </div>
                        <div class="user-signature">{{ userDetail.descri || '' }}</div>
                        <div class="user-stats">
                            <div class="stat-item"><span class="stat-value">{{ userDetail.follows || 0 }}</span><span
                                    class="stat-label">{{ $t('guan-zhu') }}</span></div>
                            <div class="stat-item"><span class="stat-value">{{ userDetail.fans || 0 }}</span><span
                                    class="stat-label">{{ $t('fen-si') }}</span></div>
                            <div class="stat-item"><span class="stat-value">{{ userDetail.friends || 0 }}</span><span
                                    class="stat-label">{{ $t('hao-you') }}</span></div>
                            <div class="stat-item"><span class="stat-value">{{ userDetail.hvisitors || 0 }}</span><span
                                    class="stat-label">{{ $t('fang-wen') }}</span></div>
                        </div>
                        <div class="user-meta">
                            <span class="user-gender" :title="userGenderTitle">
                                <i :class="userGenderIcon"></i>
                            </span>
                            <span class="user-duration">{{ formatDuration(listenTimeMins || userDetail.duration || 0) }} {{
                                $t('ting-ge-shi-chang') }}</span>
                            <span class="user-age">{{ formatRegTime(userDetail.rtime || 0) }}</span>
                        </div>
                        <div class="user-actions">
                            <span class="action-button" @click="signIn">{{ $t('qian-dao') }}</span>
                            <span class="action-button" @click="getVip">VIP</span>
                            <span class="action-button" @click="createTeamEventPopup">组队领取 VIP</span>
                        </div>
                    </div>
                </div>
            </div>
        </div>
        <div v-if="showListenSection" class="favorite-header">
            <h2 class="section-title" @click="addAllSongsToQueue">{{ $t('wo-xi-huan-ting') }}</h2>
            <button class="favorite-close-button" type="button" aria-label="close" @click="hideListenSection">
                <i class="fas fa-times"></i>
                <span>关闭</span>
            </button>
        </div>
        <div v-if="showListenSection" class="favorite-section">
            <div class="song-list">
                <CommonSkeleton v-if="isLoading" variant="compact-grid" :count="15" />
                <ul v-else>
                    <li v-for="(song, index) in listenHistory" :key="index" class="song-item"
                        @click="playSong(song['hash'], song.name.split(' - ')[1] || song.name, $getCover(song.image, 480), song.singername)">
                        <img :src="$getCover(song.image, 120)" :alt="$t('feng-mian')" class="album-cover" />
                        <div class="song-info">
                            <p class="album-name">{{ song.name.split(' - ')[1] || song.name }}</p>
                            <p class="singer-name">{{ song.singername }}</p>
                        </div>
                        <i class="song-play-icon fas fa-play"></i>
                    </li>
                </ul>
            </div>
        </div>

        <!-- 分类导航 -->
        <div class="category-tabs">
            <!-- 手机端：只保留三个（我创建的歌单 / 我关注的歌手 / 其他选项下拉） -->
            <template v-if="isMobile">
                <button :class="{ 'active': selectedCategory === MOBILE_PRIMARY_CATEGORIES[0] }"
                    @click="selectCategory(MOBILE_PRIMARY_CATEGORIES[0])">
                    {{ categories[MOBILE_PRIMARY_CATEGORIES[0]] }}
                </button>
                <button :class="{ 'active': selectedCategory === MOBILE_PRIMARY_CATEGORIES[1] }"
                    @click="selectCategory(MOBILE_PRIMARY_CATEGORIES[1])">
                    {{ categories[MOBILE_PRIMARY_CATEGORIES[1]] }}
                </button>
                <div class="category-more">
                    <button class="category-more-toggle" :class="{ 'active': isOtherCategoryActive }"
                        @click.stop="toggleCategoryMenu">
                        <span>{{ isOtherCategoryActive ? categories[selectedCategory] : $t('qi-ta-xuan-xiang') }}</span>
                        <i class="fas fa-angle-down" :class="{ 'rotated': categoryMenuOpen }"></i>
                    </button>
                    <transition name="category-more">
                        <div v-if="categoryMenuOpen" class="category-more-menu" @click.stop>
                            <button v-for="index in otherCategoryIndexes" :key="index" type="button"
                                :class="{ 'active': selectedCategory === index }"
                                @click.stop="selectCategory(index)">
                                {{ categories[index] }}
                            </button>
                        </div>
                    </transition>
                </div>
            </template>
            <!-- 桌面端：全量平铺，行为不变 -->
            <template v-else>
                <button v-for="(tab, index) in categories" :key="index" :class="{ 'active': selectedCategory === index }"
                    @click="selectCategory(index)">
                    {{ tab }}
                </button>
            </template>
        </div>

        <!-- 音乐卡片网格（显示歌单或关注的歌手） -->
        <div class="music-grid">
            <template v-if="selectedCategory === 0 || selectedCategory === 1 || selectedCategory === 2">
                <div v-if="selectedCategory === 0 && !isLoading" class="music-card create-playlist-button">
                    <router-link :to="{
                        path: '/CloudDrive'
                    }">
                        <div class="album-image-wrap">
                            <img :src="`./assets/images/cloud-disk.png`" class="album-image" />
                        </div>
                        <div class="album-info">
                            <h3>我的云盘</h3>
                            <p>(*/ω＼*)</p>
                        </div>
                    </router-link>
                </div>
                <div v-if="selectedCategory === 0 && !isLoading" class="music-card create-playlist-button">
                    <router-link :to="{
                        path: '/LocalMusic'
                    }">
                        <div class="album-image-wrap">
                            <img :src="`./assets/images/local-music.png`" class="album-image" />
                        </div>
                        <div class="album-info">
                            <h3>本地音乐</h3>
                            <p>(〃'▽'〃)</p>
                        </div>
                    </router-link>
                </div>
                <div class="music-card"
                    v-for="(item, index) in (selectedCategory === 0 ? userPlaylists : selectedCategory === 1 ? collectedPlaylists : collectedAlbums)"
                    :key="index">
                    <router-link :to="{
                        path: '/PlaylistDetail',
                        query: { global_collection_id: item.list_create_gid || item.global_collection_id, listid: item.listid }
                    }">
                        <div class="album-image-wrap">
                            <img :src="item.pic ? $getCover(item.pic, 480) : './assets/images/live.png'"
                                class="album-image" />
                        </div>
                        <div class="album-info">
                            <h3>{{ item.name }}</h3>
                            <p>{{ item.count }} <span>{{ $t('shou-ge') }}</span></p>
                        </div>
                    </router-link>
                </div>
                <div v-if="selectedCategory === 0 && !isLoading" class="music-card create-playlist-button">
                    <i class="fas fa-plus"></i>
                    <div class="album-image-wrap" @click="createPlaylist">
                        <img :src="`./assets/images/ti111mg.png`" class="album-image" />
                    </div>
                    <div class="album-info" @click="createPlaylist">
                        <h3>{{ $t('chuang-jian-ge-dan') }}</h3>
                        <p>(≧∀≦)♪</p>
                    </div>
                </div>
            </template>
            <div v-if="selectedCategory === 3 || selectedCategory === 4" class="music-card"
                v-for="(artist, index) in (selectedCategory === 3 ? followedArtists : selectedCategory === 4 ? collectedFriends : [])"
                :key="index" @click="goToArtistDetail(artist)">
                <div class="album-image-wrap">
                    <img :src="artist.pic" class="album-image" />
                </div>
                <div class="album-info">
                    <h3>{{ artist.nickname }}</h3>
                </div>
            </div>
        </div>
        <div v-if="
            (selectedCategory == 0 && userPlaylists.length === 0) ||
            (selectedCategory == 1 && collectedPlaylists.length === 0) ||
            (selectedCategory == 2 && collectedAlbums.length === 0) ||
            (selectedCategory == 3 && followedArtists.length === 0) ||
            (selectedCategory == 4 && collectedFriends.length === 0)" class="empty-container">
            <div class="empty-image">
                <img src="/assets/images/empty.png" alt="暂无数据" />
            </div>
            <div class="empty-description">{{ t('zhe-li-shi-mo-du-mei-you') }}</div>
        </div>
    </div>
</template>

<script setup>
import { computed, ref, onMounted, onUnmounted } from 'vue';
import { get, post } from '../utils/request';
import { getProfileBgColor } from '../utils/utils';
import { MoeAuthStore } from '../stores/store';
import { useListenTimeStore } from '@/stores/listenTime';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { createTeamEventPopup } from '@/utils/teamEvent';
import BirthdayEasterEgg from '../components/BirthdayEasterEgg.vue';
import CommonSkeleton from '../components/CommonSkeleton.vue';
const { t } = useI18n();
const router = useRouter();
const MoeAuth = MoeAuthStore();
const { listenTime } = useListenTimeStore();
const listenTimeMins = computed(() => Math.round(listenTime / 60));
const user = ref({});
const userPlaylists = ref([]); // 创建的歌单
const collectedPlaylists = ref([]); // 收藏的歌单
const collectedAlbums = ref([]); // 收藏的专辑
const collectedFriends = ref([]); // 好友
const followedArtists = ref([]); // 关注的歌手
const listenHistory = ref([]); // 听歌历史
const userVip = ref({});
const userDetail = ref({}); // 新增：用户详细信息
const categories = ref([t('wo-chuang-jian-de-ge-dan'), t('wo-shou-cang-de-ge-dan'), t('wo-shou-cang-de-zhuan-ji'), t('wo-guan-zhu-de-ge-shou'), t('wo-guan-zhu-de-hao-you')]);
const selectedCategory = ref(0);

// 手机端分类页签只保留三个：我创建的歌单、我关注的歌手，其余收进「其他选项」下拉。
// 桌面端仍按 categories 全量平铺。
// 必须和下方 @media (max-width: 768px) 保持同一个值，否则正好 768px 时
// CSS 按手机布局渲染、这里却走桌面分支，页签会出现重排
const MOBILE_QUERY = '(max-width: 768px)';
const isMobile = ref(typeof window !== 'undefined' && window.matchMedia(MOBILE_QUERY).matches);
const MOBILE_PRIMARY_CATEGORIES = [0, 3];
const otherCategoryIndexes = computed(() =>
    categories.value.map((_, index) => index).filter(index => !MOBILE_PRIMARY_CATEGORIES.includes(index))
);
const isOtherCategoryActive = computed(() => otherCategoryIndexes.value.includes(selectedCategory.value));
const categoryMenuOpen = ref(false);
const toggleCategoryMenu = () => {
    categoryMenuOpen.value = !categoryMenuOpen.value;
};
// 点击「其他选项」外部时收起下拉
const handleCategoryMenuOutside = (event) => {
    if (!categoryMenuOpen.value) return;
    if (event.target?.closest?.('.category-more')) return;
    categoryMenuOpen.value = false;
};
let mobileQuery = null;
const onMobileQueryChange = (e) => {
    isMobile.value = e.matches;
    // 切回桌面端时收起下拉，避免残留浮层
    if (!e.matches) categoryMenuOpen.value = false;
};
const isLoading = ref(true);
const LISTEN_SECTION_HIDDEN_KEY = 'library:listen-section-hidden';
const DEFAULT_PROFILE_BG_COLOR = 'rgb(44, 32, 34)';
const isListenSectionHidden = ref(localStorage.getItem(LISTEN_SECTION_HIDDEN_KEY) === '1');
const showListenSection = computed(() => !isListenSectionHidden.value && (isLoading.value || listenHistory.value.length > 0));
const profileBgColor = ref(DEFAULT_PROFILE_BG_COLOR);
const profileBackgroundImage = ref('');
const profileHeaderStyle = computed(() => ({
    '--profile-bg-image': profileBackgroundImage.value ? `url(${profileBackgroundImage.value})` : 'none',
    '--profile-bg-color': profileBgColor.value
}));
const userGenderIcon = computed(() => {
    const gender = userDetail.value.gender;
    if (gender === 1) return 'fas fa-mars';
    if (gender === 0) return 'fas fa-venus';
    return 'fas fa-user-secret';
});
const userGenderTitle = computed(() => {
    const gender = userDetail.value.gender;
    if (gender === 1) return '男';
    if (gender === 0) return '女';
    return '保密';
});

const selectCategory = (index) => {
    selectedCategory.value = index;
    categoryMenuOpen.value = false;
    // router.replace({ path: '/library', query: { category: index } });
};

// 格式化听歌时长（分钟转为小时和分钟）
const formatDuration = (minutes) => {
    if (!minutes) return '0';
    const hours = Math.floor(minutes / 60);
    const mins = minutes % 60;
    if (hours > 0) {
        return `${hours}${t('xiao-shi')} ${mins}${t('fen-zhong')}`;
    }
    return `${mins}${t('fen-zhong')}`;
};

// 格式化注册时间
const formatRegTime = (timestamp) => {
    if (!timestamp) return '';
    const registerDate = new Date(timestamp * 1000);
    const now = new Date();
    const years = now.getFullYear() - registerDate.getFullYear();
    return `${t('le-ling')} ${years} ${t('nian')}`;
};

const updateProfileBackground = (src) => {
    const targetSrc = src;
    const image = new Image();
    image.onload = () => {
        profileBackgroundImage.value = targetSrc;
    };
    image.src = targetSrc;
    profileBgColor.value = DEFAULT_PROFILE_BG_COLOR;
    getProfileBgColor(targetSrc).then(color => {
        profileBgColor.value = color;
    }).catch(() => {
        profileBgColor.value = DEFAULT_PROFILE_BG_COLOR;
    });
};

const playSong = (hash, name, img, author) => {
    props.playerControl.addSongToQueue(hash, name, img, author);
};

const props = defineProps({
    playerControl: Object
});

onMounted(() => {
    mobileQuery = window.matchMedia(MOBILE_QUERY);
    mobileQuery.addEventListener('change', onMobileQueryChange);
    document.addEventListener('click', handleCategoryMenuOutside);
    if (MoeAuth.isAuthenticated) {
        user.value = MoeAuth.UserInfo;
        // 获取用户vip信息
        getVipInfo();
    }
});

onUnmounted(() => {
    document.removeEventListener('click', handleCategoryMenuOutside);
    if (mobileQuery) {
        mobileQuery.removeEventListener('change', onMobileQueryChange);
        mobileQuery = null;
    }
});
const getUserDetails = () => {
    // 获取用户详细信息
    getUserDetail();
    // 获取用户听歌历史
    const listenTask = isListenSectionHidden.value ? Promise.resolve() : getlisten();
    listenTask.finally(() => {
        isLoading.value = false;
    })
    // 获取用户创建和收藏的歌单
    getplaylist()
    // 获取用户关注的歌手
    getfollow()
    selectedCategory.value = parseInt(router.currentRoute.value.query.category || 0);
}

// 获取用户详细信息
const getUserDetail = async () => {
    try {
        const detailResponse = await get('/user/detail');
        if (detailResponse.status === 1) {
            userDetail.value = detailResponse.data;
            updateProfileBackground(userDetail.value.bg_pic || './assets/images/banner.png');
        }
    } catch (error) {
        console.error('Failed to get user details:', error);
    }
}

const getVipInfo = async () => {
    try {
        const VipInfoResponse = await get('/user/vip/detail');
        if (VipInfoResponse.status === 1) {
            userVip.value = VipInfoResponse.data.busi_vip
            getUserDetails();
        }
    } catch (error) {
        window.$modal.alert(t('deng-lu-shi-xiao-qing-zhong-xin-deng-lu'));
        router.push('/login');
    }
}

const getlisten = async () => {
    const historyResponse = await get('/user/listen', { type: 1 });
    if (historyResponse.status === 1) {
        const allLists = historyResponse.data.lists;
        const shuffled = allLists.sort(() => 0.5 - Math.random());
        listenHistory.value = shuffled.slice(0, 20);
    }
}
const hideListenSection = () => {
    localStorage.setItem(LISTEN_SECTION_HIDDEN_KEY, '1');
    isListenSectionHidden.value = true;
    listenHistory.value = [];
    isLoading.value = false;
}
const getfollow = async () => {
    const followResponse = await get('/user/follow');
    if (followResponse.status === 1) {
        if (followResponse.data.total == 0) return;
        const artists = followResponse.data.lists.map(artist => ({
            ...artist,
            pic: artist.pic.replace('/100/', '/480/')
        }));
        collectedFriends.value = artists.filter(artist => !artist.singerid);
        followedArtists.value = artists.filter(artist => artist.source == 7);
    }
}
const getplaylist = async () => {
    try {
        const playlistResponse = await get('/user/playlist', {
            pagesize: 500,
            t: localStorage.getItem('t')
        });
        if (playlistResponse.status === 1) {
            const sortedInfo = playlistResponse.data.info.sort((a, b) => {
                if (a.sort !== b.sort) {
                    return a.sort - b.sort;
                }
                return 0;
            });

            userPlaylists.value = sortedInfo.filter(playlist => {
                if (playlist.name == '我喜欢') {
                    localStorage.setItem('like', playlist.listid);
                }
                return playlist.list_create_userid === user.value.userid || playlist.name === '我喜欢';
            }).sort((a, b) => a.name === '我喜欢' ? -1 : 1);

            collectedPlaylists.value = sortedInfo.filter(playlist =>
                playlist.list_create_userid !== user.value.userid && !playlist.authors
            );

            collectedAlbums.value = sortedInfo.filter(playlist =>
                playlist.list_create_userid !== user.value.userid && playlist.authors
            );

            const collectedIds = [];
            sortedInfo.forEach(playlist => {
                if (playlist.list_create_userid !== user.value.userid) {
                    collectedIds.push({
                        list_create_listid: playlist.list_create_listid,
                        listid: playlist.listid
                    });
                }
            });
            localStorage.setItem('collectedPlaylists', JSON.stringify(collectedIds));
        }
    } catch (error) {
        window.$modal.alert(t('xin-zeng-zhang-hao-qing-xian-zai-guan-fang-ke-hu-duan-zhong-deng-lu-yi-ci'));
    }
}
const createPlaylist = async () => {
    const result = await window.$modal.prompt(t('qing-shu-ru-xin-de-ge-dan-ming-cheng'), '');
    if (result) {
        try {
            const playlistResponse = await post('/playlist/add', { name: result, list_create_userid: user.value.userid, type: 0 });
            if (playlistResponse.status === 1) {
                localStorage.setItem('t', Date.now());
                getplaylist()
            } else {
                window.$modal.alert(`${t('chuang-jian-shi-bai')} (error_code: ${playlistResponse.error_code ?? 'undefined'})`);
                console.error('创建歌单失败:', playlistResponse);
            }
        } catch (error) {
            window.$modal.alert(t('chuang-jian-shi-bai'));
        }
    }
}

const goToArtistDetail = (artist) => {
    if (!artist.singerid) return;
    router.push({
        path: '/PlaylistDetail',
        query: {
            singerid: artist.singerid,
            unfollow: true
        }
    });
};
const signIn = async () => {
    try {
        const res = await get('/youth/vip');
        if (res.status === 1) {
            window.$modal.alert(`签到成功，获得${res.data.award_vip_hour}小时VIP时长`);
        }
    } catch (error) {
        window.$modal.alert('签到失败![该接口将在未来被移除]');
    }
}
const getVip = async () => {
    try {
        const todayKey = new Date().toLocaleDateString('zh-CN', {
            year: 'numeric', month: '2-digit', day: '2-digit'
        }).replace(/\//g, '-');
        const vipResponse = await get('/youth/day/vip', {
            receive_day: todayKey
        });
        const result = await window.$modal.confirm('是否继续升级至概念版VIP,享受更高音质?');
        if (result) {
            try {
                const vipResponse = await get('/youth/day/vip/upgrade');
                if (vipResponse.status === 1) {
                    window.$modal.alert('升级成功，获得1天概念版VIP');
                }
            } catch (error) {
                window.$modal.alert(error.error_msg || '升级VIP失败, 一天仅限一次');
            }
        } else if (vipResponse.status === 1) {
            window.$modal.alert(`签到成功，获得1天畅听VIP`);
        }
    } catch (error) {
        if (error.response.data.error_code == 131001) {
            window.$modal.alert('你今天已经签到过了');
            return;
        } else if (error.response.data.error_code == 20028) {
            window.$modal.alert('当前账号风控,请前往手机端领取');
            return;
        }
        window.$modal.alert('获取VIP失败-' + error.response.data.error_code);
    }
}
const addAllSongsToQueue = () => {
    props.playerControl.addPlaylistToQueue(listenHistory.value.map(song => ({
        hash: song.hash,
        name: song.name,
        cover: song.image?.replace("{size}", 480),
        author: song.author_name,
        timelen: song.duration
    })));
};
</script>

<style lang="scss" scoped>
.sign-in {
    cursor: pointer;
    color: var(--primary-color);
    margin-left: 10px;
    border-radius: 5px;
    padding: 2px 8px;
    border: 1px solid var(--primary-color);
    font-size: 12px;
}

.library-page {
    padding: 20px;
    --library-favorite-card-bg: rgba(255, 255, 255, 0.9);
    --library-favorite-card-hover-bg: rgba(var(--primary-color-rgb), 0.08);
    --library-favorite-title: #2b2b2b;
    --library-favorite-text: #666;
}

:global(.dark) .library-page {
    --library-favorite-card-bg: rgba(39, 39, 39, 0.92);
    --library-favorite-card-hover-bg: rgba(var(--primary-color-rgb), 0.14);
    --library-favorite-title: rgba(255, 255, 255, 0.88);
    --library-favorite-text: rgba(255, 255, 255, 0.58);
}

.user-level {
    width: 50px;
    margin-left: 10px;
    cursor: pointer;
}

.section-title {
    font-size: 28px;
    font-weight: bold;
    color: var(--primary-color);
    cursor: cell;
    margin-bottom: 0px;
    display: inline-block;
}

.favorite-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
}

.favorite-close-button {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 0;
    border: none!important;
    background-color: transparent!important;
    color: transparent!important;
    cursor: pointer;
    transition: color 0.2s ease;

    &:is(.dark .favorite-close-button ){
        background-color: transparent!important;
    }

    &:hover {
        color: #8a8a8a!important;
    }
}

.profile-section {
    display: flex;
    align-items: center;
}

.profile-header {
    width: 100%;
    height: 100%;
    min-height: 164px;
    border-radius: 15px;
    margin-bottom: 20px;
    display: flex;
    align-items: flex-end;
    padding: 20px;
    box-shadow: 0 4px 8px rgba(0, 0, 0, 0.1);
    position: relative;
    overflow: hidden;
    transition: background-image 1s ease-in-out;
    background-color: var(--profile-bg-color);
}

.profile-background-image-wrap,
.profile-background-main,
.profile-background-top,
.profile-background-bottom,
.profile-background-right {
    position: absolute;
    top: 0;
    right: 0;
    border-radius: 15px;
    pointer-events: none;
}

.profile-background-image-wrap {
    width: min(46%, 680px);
    height: 100%;
    z-index: 0;
    overflow: hidden;
}

.profile-background-image {
    width: 100%;
    height: calc(100% + 96px);
    margin-top: -48px;
    background-image: var(--profile-bg-image);
    background-repeat: no-repeat;
    background-position: center;
    background-size: cover;
    opacity: 0.42;
}

.profile-background-main {
    inset: 0;
    background-image: linear-gradient(90deg,
            var(--profile-bg-color) 0%,
            var(--profile-bg-color) 54%,
            rgba(28, 26, 34, 0.68) 76%,
            rgba(28, 26, 34, 0.18) 100%);
    z-index: 1;
}

.profile-background-top {
    inset: 0;
    background-image: linear-gradient(180deg, rgba(255, 255, 255, 0.08) 0%, rgba(255, 255, 255, 0) 46%);
    z-index: 2;
}

.profile-background-bottom {
    left: 0;
    width: 100%;
    height: 58%;
    top: auto;
    bottom: 0;
    background-image: linear-gradient(180deg, rgba(15, 16, 22, 0) 0%, rgba(15, 16, 22, 0.6) 100%);
    z-index: 2;
}

.profile-background-right {
    width: 220px;
    height: 100%;
    background-image: linear-gradient(90deg, rgba(255, 255, 255, 0) 0%, rgba(255, 255, 255, 0.08) 100%);
    z-index: 2;
}

.profile-info {
    display: flex;
    align-items: flex-end;
    gap: 15px;
    color: white;
    text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.5);
    width: 100%;
    z-index: 3;
}

.profile-pic {
    border-radius: 50%;
    width: 90px;
    height: 90px;
    border: 3px solid white;
    box-shadow: 0 0 10px rgba(0, 0, 0, 0.3);
    margin-bottom: 10px;
    position: relative;
    top: -20px;
}

.user-details {
    display: flex;
    flex-direction: column;
    justify-content: flex-end;
    height: 100%;
    flex: 1;
}

.user-name-row {
    display: flex;
    align-items: center;
    margin-bottom: 2px;
}

.user-name {
    font-size: 28px;
    font-weight: bold;
    margin: 0;
}

.user-level {
    font-size: 14px;
    background-color: rgba(255, 255, 255, 0.2);
    padding: 2px 8px;
    border-radius: 10px;
    color: white;
}

.user-vip-icon {
    height: 22px;
    margin-left: 10px;
}

.user-signature {
    font-size: 14px;
    color: #eee;
    margin-bottom: 8px;
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 1;
    -webkit-box-orient: vertical;
}

.user-stats {
    display: flex;
    justify-content: flex-start;
    gap: 20px;
    margin-bottom: 5px;
    font-size: 14px;
    color: #fff;
}

.stat-item {
    text-align: center;
}

.stat-value {
    font-size: 18px;
    font-weight: bold;
    display: inline-block;
    margin-right: 3px;
}

.stat-label {
    display: inline-block;
    font-size: 12px;
    color: rgba(255, 255, 255, 0.8);
}

.user-meta {
    display: flex;
    align-items: center;
    gap: 15px;
    font-size: 12px;
    color: #fff;
    margin-bottom: 10px;
}

.user-gender i {
    font-size: 16px;
    color: #fff;
}

.user-duration,
.user-age {
    background-color: rgba(255, 255, 255, 0.2);
    padding: 3px 8px;
    border-radius: 10px;
    color: white;
}

.user-actions {
    display: flex;
    gap: 10px;
    margin-top: 5px;
}

.action-button {
    background-color: rgba(255, 255, 255, 0.2);
    padding: 4px 10px;
    border-radius: 10px;
    color: white;
    cursor: pointer;
    font-size: 12px;
    border: 1px solid rgba(255, 255, 255, 0.3);
    transition: background-color 0.3s ease;

    &:hover {
        background-color: rgba(255, 255, 255, 0.3);
    }
}

.favorite-section {
    display: flex;
    justify-content: space-between;
    padding-top: 15px;
    padding-bottom: 15px;
}

.favorite-playlist {
    background-color: var(--background-color);
    padding: 20px;
    border-radius: 12px;
    flex: 1;
    margin-right: 20px;
    border: 1px solid var(--secondary-color);
    box-shadow: 0 4px 8px rgba(0, 0, 0, 0.1);
    margin-bottom: 20px;
}

.playlist-info p {
    margin: 10px 0;
}

.play-button {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    background-color: var(--secondary-color);
    color: white;
    border: none;
    border-radius: 25px;
    padding: 10px 15px;
    cursor: pointer;

    i {
        font-size: 16px;
    }
}

.song-list {
    width: 100%;
    background: transparent;

    ul {
        list-style: none;
        padding: 0;
        margin: 0;
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
        gap: 10px;
    }

    li {
        position: relative;
        display: flex;
        align-items: center;
        gap: 10px;
        min-width: 0;
        min-height: 64px;
        cursor: pointer;
        border-radius: 8px;
        padding: 7px 40px 7px 8px;
        background-color: var(--library-favorite-card-bg);
        transition: transform 0.2s ease, background-color 0.2s ease, box-shadow 0.2s ease;

        &:hover {
            transform: translateY(-2px);
            background-color: var(--library-favorite-card-hover-bg);
            box-shadow: 0 10px 20px rgba(0, 0, 0, 0.08);

            .song-play-icon {
                opacity: 1;
                transform: translateY(-50%) scale(1);
            }
        }
    }

    img {
        width: 50px;
        height: 50px;
        flex-shrink: 0;
        border-radius: 8px;
        object-fit: cover;
    }
}

.category-tabs {
    display: flex;
    gap: 20px;
    margin-bottom: 20px;

    button {
        position: relative;
        overflow: hidden;
        padding: 10px 15px;
        border: none;
        background-color: #f5f5f5;
        border-radius: 20px;
        cursor: pointer;
        transition: background-color 0.25s ease, color 0.25s ease, box-shadow 0.25s ease, transform 0.25s ease;

        &::after {
            content: "";
            position: absolute;
            inset: 0;
            background: linear-gradient(120deg, transparent 0%, rgba(255, 255, 255, 0.45) 45%, transparent 70%);
            transform: translateX(-120%);
            pointer-events: none;
        }

        &:hover {
            transform: translateY(-2px);
        }

        &.active {
            background-color: var(--primary-color);
            color: white;
            box-shadow: 0 8px 18px rgba(0, 0, 0, 0.12);
            transform: translateY(-2px);
            animation: categoryActivePop 0.28s cubic-bezier(0.22, 1, 0.36, 1);

            &::after {
                animation: categoryShine 0.55s ease;
            }
        }
    }
}

@keyframes categoryActivePop {
    0% {
        transform: translateY(0) scale(0.96);
    }

    100% {
        transform: translateY(-2px) scale(1);
    }
}

@keyframes categoryShine {
    0% {
        transform: translateX(-120%);
    }

    100% {
        transform: translateX(120%);
    }
}

.music-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 20px;
}

.music-card {
    min-width: 0;
    border-radius: 8px;
    padding-bottom: 10px;
    text-align: center;
    cursor: pointer;
    transition: transform 0.22s ease, background-color 0.22s ease, box-shadow 0.22s ease;

    a {
        display: block;
        color: inherit;
        text-decoration: none;
    }

    &:hover {
        transform: translateY(-4px);

        .album-image-wrap {
            box-shadow: 0 10px 22px rgba(0, 0, 0, 0.16);
        }

        .album-image {
            transform: scale(1.06);
            filter: saturate(1.06);
        }
    }
}

:global(.dark) .music-card:hover {
    background-color: rgba(var(--primary-color-rgb), 0.12);
}

.album-image {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: transform 0.28s ease, filter 0.22s ease;
}

.album-image-wrap {
    width: 100%;
    aspect-ratio: 1;
    overflow: hidden;
    border-radius: 8px;
    transition: box-shadow 0.22s ease;
}

.album-info {
    h3 {
        margin: 10px 0 5px;
        font-size: 16px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }

    p {
        margin: 0;
        color: #666;
        font-size: 14px;
    }
}

.song-item {
    display: flex;
    align-items: center;
}

.album-cover {
    width: 50px;
    height: 50px;
    border-radius: 8px;
}

.song-info {
    display: flex;
    flex-direction: column;
    justify-content: center;
    min-width: 0;
    flex: 1;
}

.album-name,
.singer-name {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.album-name {
    font-weight: bold;
    margin: 0 0 4px;
    font-size: 14px;
    color: var(--library-favorite-title);
}

.singer-name {
    margin: 0;
    font-size: 12px;
    color: var(--library-favorite-text);
}

.song-play-icon {
    position: absolute;
    top: 50%;
    right: 14px;
    width: 26px;
    height: 26px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    background-color: var(--primary-color);
    color: #fff;
    font-size: 10px;
    opacity: 0;
    transform: translateY(-50%) scale(0.9);
    transition: opacity 0.2s ease, transform 0.2s ease;
}

.create-playlist-button {
    color: var(--primary-color);
    border-radius: 10px;
    cursor: pointer;
    position: relative;

    i {
        font-size: 30px;
        position: absolute;
        top: 32%;
        left: 29%;
        z-index: 1;
        pointer-events: none;
    }
}

.empty-container {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 40px 0;
    width: 100%;
}

.empty-image {
    margin-bottom: 20px;
    display: flex;
    justify-content: center;

    img {
        width: 200px;
        height: 200px;
        opacity: 0.6;
    }
}

.empty-description {
    color: #909399;
    font-size: 14px;
    text-align: center;
    margin-left: 60px;
}

// 手机视口：资料头部原本是「头像 + 文字」横排，可用宽只有 ~184px，
// 昵称 28px 会被压成三四行、四个统计项和三个操作按钮挤成一团。
// 这里改成头像在上、信息在下，并让各信息行可换行、按钮不拆字。
@media (max-width: 768px) {
    .library-page {
        padding: 12px;
    }

    .profile-header {
        min-height: 0;
        padding: 14px;
        align-items: flex-start;
    }

    .profile-info {
        flex-direction: column;
        align-items: flex-start;
        gap: 10px;
    }

    .profile-pic {
        width: 64px;
        height: 64px;
        top: 0;
        margin-bottom: 0;
        border-width: 2px;
    }

    .user-details {
        width: 100%;
        height: auto;
        justify-content: flex-start;
    }

    .user-name-row {
        flex-wrap: wrap;
        gap: 4px 8px;
    }

    .user-name {
        font-size: 20px;
        max-width: 100%;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }

    .user-level {
        width: auto;
        margin-left: 0;
        font-size: 12px;
    }

    .user-vip-icon {
        height: 18px;
        margin-left: 0;
    }

    .user-signature {
        font-size: 12px;
        margin-bottom: 6px;
    }

    .user-stats {
        flex-wrap: wrap;
        gap: 12px;
        font-size: 12px;
        margin-bottom: 6px;
    }

    .stat-value {
        font-size: 15px;
    }

    .user-meta {
        flex-wrap: wrap;
        gap: 8px;
        margin-bottom: 8px;
    }

    .user-actions {
        flex-wrap: wrap;
        gap: 8px;
        margin-top: 0;
    }

    // 用户卡片底部这三个是次要操作，文字不拆行，
    // 且不能用页签那套 40px：原规则在 content-box 下会变成
    // 40 + padding8 + border2 = 50px 高的方块，把卡片下沿压得很重，
    // 这里显式用 border-box 收成紧凑胶囊。
    .action-button {
        white-space: nowrap;
        box-sizing: border-box;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        height: 34px;
        min-height: 0;
        padding: 0 12px;
    }

    .section-title {
        font-size: 22px;
    }

    // 分类页签固定三个：两个主分类 +「其他选项」下拉，不再横向滑动
    .category-tabs {
        gap: 8px;
        margin-bottom: 16px;

        >button,
        .category-more {
            flex: 1 1 0;
            min-width: 0;
        }

        button {
            padding: 8px 10px;
            font-size: 13px;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
    }

    .category-more {
        position: relative;

        .category-more-toggle {
            width: 100%;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 4px;

            i {
                flex-shrink: 0;
                font-size: 12px;
                transition: transform 0.18s ease;

                &.rotated {
                    transform: rotate(180deg);
                }
            }
        }

        // 浮层不参与页签等分宽度
        .category-more-menu {
            position: absolute;
            top: calc(100% + 6px);
            right: 0;
            z-index: 20;
            min-width: 148px;
            display: flex;
            flex-direction: column;
            gap: 2px;
            padding: 6px;
            border-radius: 12px;
            background-color: var(--background-color);
            box-shadow: 0 8px 24px rgba(0, 0, 0, 0.16);

            button {
                width: 100%;
                text-align: left;
                padding: 8px 10px;
                border-radius: 8px;
                background: transparent;

                &:hover {
                    transform: none;
                }
            }
        }
    }

    .category-more-enter-active,
    .category-more-leave-active {
        transition: opacity 0.15s ease, transform 0.15s ease;
    }

    .category-more-enter-from,
    .category-more-leave-to {
        opacity: 0;
        transform: translateY(-4px);
    }

    .music-grid {
        grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
        gap: 12px;
    }

    /* 触屏没有 hover，只有 :hover 才可见的入口要显式放出来：
       收藏区关闭按钮默认 color:transparent，播放图标同理 */
    .favorite-close-button {
        color: #8a8a8a !important;
        min-width: 44px;
        min-height: 44px;
        justify-content: center;
    }

    .song-play-icon {
        opacity: 1;
        transform: translateY(-50%) scale(1);
    }

    .category-tabs button,
    .category-more-toggle {
        min-height: 40px;
    }

    .playlist-name,
    .playlist-description,
    .song-name {
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .empty-description {
        margin-left: 0;
        padding: 0 16px;
    }
}

/* 横屏低高度：资料头部不再吃掉整屏 */
@media (max-height: 480px) and (max-width: 900px) {
    .profile-header {
        min-height: 0;
        padding: 12px;
    }
}

</style>
