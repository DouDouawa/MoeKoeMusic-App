<template>
    <div class="ranking-page">
        <RankingContent :player-control="playerControl" />
    </div>
</template>

<script setup>
import RankingContent from '../components/discover/RankingContent.vue';

defineProps({
    playerControl: Object
});
</script>

<style scoped>
.ranking-page {
    padding: 20px;
}
@media (max-width: 768px) {
    .ranking-page {
        padding: 12px;
    }
}
</style>
