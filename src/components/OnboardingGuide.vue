<script setup lang="ts">
import { ref } from 'vue'
import { Car, ChevronLeft, Cloud, Fuel, Gauge, ShieldCheck } from '@lucide/vue'
import ModalFrame from './ModalFrame.vue'

defineProps<{ vehicleName: string }>()
const emit = defineEmits<{ finish: []; record: []; vehicle: [] }>()
const step = ref(0)

const steps = [
  {
    eyebrow: '第一步 · 认识你的车',
    title: '从一辆车开始',
    description: '确认车辆名称、燃油标号和当前里程，之后的每笔记录都会归入它的专属账本。',
    icon: Car,
  },
  {
    eyebrow: '第二步 · 记录满箱',
    title: '两次满箱，算出真实油耗',
    description: '油迹会累计两次满箱之间的加油量，并结合行驶里程计算区间油耗；途中部分加油也不会丢失。',
    icon: Gauge,
  },
  {
    eyebrow: '第三步 · 数据归你',
    title: '离线可用，也能安心同步',
    description: '记录默认保存在本机。你可以随时导出备份，或连接自己的 WebDAV 空间进行多设备同步。',
    icon: ShieldCheck,
  },
]
</script>

<template>
  <ModalFrame labelled-by="onboarding-title" size="small" @close="emit('finish')">
    <div class="onboarding">
      <header class="onboarding-brand"><span><Fuel :size="18" /></span><strong>油迹</strong><small>新手上路</small></header>
      <div class="onboarding-visual">
        <span class="onboarding-orbit" />
        <span class="onboarding-icon"><component :is="steps[step].icon" :size="38" /></span>
        <Cloud v-if="step === 2" class="onboarding-cloud" :size="22" />
      </div>
      <div class="onboarding-copy">
        <span class="eyebrow">{{ steps[step].eyebrow }}</span>
        <h2 id="onboarding-title">{{ steps[step].title }}</h2>
        <p>{{ steps[step].description }}</p>
        <p v-if="step === 0" class="onboarding-current">当前车辆：<strong>{{ vehicleName }}</strong></p>
      </div>
      <div class="onboarding-dots" aria-label="引导进度">
        <span v-for="(_, index) in steps" :key="index" :class="{ active: index === step }">{{ index + 1 }}</span>
      </div>
      <footer class="onboarding-actions">
        <button v-if="step === 0" class="text-button onboarding-skip" @click="emit('finish')">跳过引导</button>
        <button v-else class="button subtle" @click="step--"><ChevronLeft :size="17" />上一步</button>
        <button v-if="step < steps.length - 1" class="button primary" @click="step++">下一步</button>
        <button v-else class="button primary" @click="emit('record')">记第一笔加油</button>
      </footer>
      <button v-if="step === 0" class="onboarding-vehicle" @click="emit('vehicle')">先完善车辆信息</button>
    </div>
  </ModalFrame>
</template>
