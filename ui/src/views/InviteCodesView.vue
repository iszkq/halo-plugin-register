<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { axiosInstance } from "@halo-dev/api-client";

type InviteCode = {
  apiVersion: "invitecode.plugin.halo.local/v1alpha1";
  kind: "InviteCode";
  metadata: {
    name: string;
    version?: number;
    creationTimestamp?: string;
  };
  spec: {
    code: string;
    normalizedCode: string;
    enabled: boolean;
    maxUses?: number | null;
    usedCount?: number | null;
    expireAt?: string | null;
    note?: string | null;
  };
};

type ListResult<T> = {
  items: T[];
  page: number;
  size: number;
  total: number;
};

const BASE_PATH = "/apis/invitecode.plugin.halo.local/v1alpha1/invitecodes";

const loading = ref(false);
const saving = ref(false);
const errorMessage = ref("");
const successMessage = ref("");
const inviteCodes = ref<InviteCode[]>([]);

const form = reactive({
  code: "",
  enabled: true,
  maxUses: "",
  expireAt: "",
  note: "",
});

const hasData = computed(() => inviteCodes.value.length > 0);
const totalCount = computed(() => inviteCodes.value.length);
const activeCount = computed(() =>
  inviteCodes.value.filter((item) => item.spec.enabled && !isExpired(item)).length,
);
const expiredCount = computed(() => inviteCodes.value.filter((item) => isExpired(item)).length);
const totalUsedCount = computed(() =>
  inviteCodes.value.reduce((total, item) => total + (item.spec.usedCount || 0), 0),
);

function randomSuffix() {
  return Math.random().toString(36).slice(2, 8);
}

function slugify(value: string) {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 40);
}

function toPayload(): InviteCode {
  const code = form.code.trim();
  return {
    apiVersion: "invitecode.plugin.halo.local/v1alpha1",
    kind: "InviteCode",
    metadata: {
      name: `${slugify(code) || "invite"}-${randomSuffix()}`,
    },
    spec: {
      code,
      normalizedCode: code.toLowerCase(),
      enabled: form.enabled,
      maxUses: form.maxUses ? Number(form.maxUses) : null,
      usedCount: 0,
      expireAt: form.expireAt ? new Date(form.expireAt).toISOString() : null,
      note: form.note.trim() || null,
    },
  };
}

function formatDate(value?: string | null) {
  if (!value) {
    return "未设置";
  }
  return new Date(value).toLocaleString();
}

function usageText(item: InviteCode) {
  const used = item.spec.usedCount || 0;
  const max = item.spec.maxUses || 0;
  return max > 0 ? `${used} / ${max}` : `${used} / 不限`;
}

function isExpired(item: InviteCode) {
  if (!item.spec.expireAt) {
    return false;
  }
  return new Date(item.spec.expireAt).getTime() < Date.now();
}

function statusText(item: InviteCode) {
  if (isExpired(item)) {
    return "已过期";
  }
  return item.spec.enabled ? "启用中" : "已停用";
}

function statusClass(item: InviteCode) {
  if (isExpired(item)) {
    return "invite-badge--warn";
  }
  return item.spec.enabled ? "invite-badge--ok" : "invite-badge--mute";
}

function removeInviteCode(name: string) {
  inviteCodes.value = inviteCodes.value.filter((item) => item.metadata.name !== name);
}

function isNotFoundError(error: any) {
  return Number(error?.response?.status) === 404;
}

async function loadInviteCodes() {
  loading.value = true;
  errorMessage.value = "";
  try {
    const { data } = await axiosInstance.get<ListResult<InviteCode>>(BASE_PATH, {
      params: {
        page: 1,
        size: 200,
        sort: "metadata.creationTimestamp,desc",
      },
    });
    inviteCodes.value = data.items || [];
  } catch (error: any) {
    errorMessage.value = error?.response?.data?.message || "加载邀请码失败";
  } finally {
    loading.value = false;
  }
}

async function createInviteCode() {
  if (!form.code.trim()) {
    errorMessage.value = "请先输入邀请码";
    return;
  }
  saving.value = true;
  errorMessage.value = "";
  successMessage.value = "";
  try {
    await axiosInstance.post(BASE_PATH, toPayload());
    successMessage.value = "邀请码已创建";
    form.code = "";
    form.enabled = true;
    form.maxUses = "";
    form.expireAt = "";
    form.note = "";
    await loadInviteCodes();
  } catch (error: any) {
    errorMessage.value = error?.response?.data?.message || "创建邀请码失败";
  } finally {
    saving.value = false;
  }
}

async function toggleInviteCode(item: InviteCode) {
  errorMessage.value = "";
  successMessage.value = "";
  try {
    await axiosInstance.put(`${BASE_PATH}/${item.metadata.name}`, {
      ...item,
      spec: {
        ...item.spec,
        enabled: !item.spec.enabled,
      },
    });
    successMessage.value = item.spec.enabled ? "邀请码已停用" : "邀请码已启用";
    await loadInviteCodes();
  } catch (error: any) {
    errorMessage.value = error?.response?.data?.message || "更新邀请码失败";
  }
}

async function deleteInviteCode(item: InviteCode) {
  const confirmed = window.confirm(`确定删除邀请码 ${item.spec.code} 吗？`);
  if (!confirmed) {
    return;
  }
  const inviteName = item.metadata.name;
  errorMessage.value = "";
  successMessage.value = "";
  try {
    await axiosInstance.delete(`${BASE_PATH}/${inviteName}`);
    removeInviteCode(inviteName);
    successMessage.value = "邀请码已删除";
  } catch (error: any) {
    if (isNotFoundError(error)) {
      removeInviteCode(inviteName);
      successMessage.value = "邀请码已删除";
      return;
    }
    errorMessage.value = error?.response?.data?.message || "删除邀请码失败";
  }
}

onMounted(loadInviteCodes);
</script>

<template>
  <div class="invite-page">
    <section class="invite-hero">
      <div class="invite-hero__main">
        <span class="invite-eyebrow">Invite Register</span>
        <h1 class="invite-title invite-title--hero">邀请码注册</h1>
        <p class="invite-desc invite-desc--hero">
          管理邀请码的创建、状态、使用次数和有效期，让注册准入更清晰，也更有秩序。
        </p>
      </div>
      <div class="invite-stats">
        <article class="invite-stat-card">
          <span class="invite-stat-card__label">邀请码总数</span>
          <strong class="invite-stat-card__value">{{ totalCount }}</strong>
        </article>
        <article class="invite-stat-card">
          <span class="invite-stat-card__label">可用邀请码</span>
          <strong class="invite-stat-card__value">{{ activeCount }}</strong>
        </article>
        <article class="invite-stat-card">
          <span class="invite-stat-card__label">累计使用</span>
          <strong class="invite-stat-card__value">{{ totalUsedCount }}</strong>
        </article>
        <article class="invite-stat-card">
          <span class="invite-stat-card__label">已过期</span>
          <strong class="invite-stat-card__value">{{ expiredCount }}</strong>
        </article>
      </div>
    </section>

    <section class="invite-card">
      <div class="invite-card__header">
        <div>
          <h2 class="invite-title">新增邀请码</h2>
          <p class="invite-desc">填写邀请码信息并立即创建，留空最大次数表示不限。</p>
        </div>
      </div>

      <div class="invite-grid">
        <div class="invite-field">
          <label for="code">邀请码</label>
          <input id="code" v-model.trim="form.code" placeholder="例如 VIP2026" />
        </div>
        <div class="invite-field">
          <label for="maxUses">最大使用次数</label>
          <input id="maxUses" v-model="form.maxUses" type="number" min="0" placeholder="留空表示不限" />
        </div>
        <div class="invite-field">
          <label for="expireAt">过期时间</label>
          <input id="expireAt" v-model="form.expireAt" type="datetime-local" />
        </div>
        <div class="invite-field">
          <label for="enabled">状态</label>
          <select id="enabled" v-model="form.enabled">
            <option :value="true">启用</option>
            <option :value="false">停用</option>
          </select>
        </div>
      </div>

      <div class="invite-field invite-field--full">
        <label for="note">备注</label>
        <textarea id="note" v-model.trim="form.note" placeholder="可选，例如：活动用户、内部测试"></textarea>
      </div>

      <div class="invite-toolbar">
        <button class="invite-button invite-button--primary" :disabled="saving" @click="createInviteCode">
          {{ saving ? "创建中..." : "创建邀请码" }}
        </button>
        <span class="invite-tip">过期时间为空表示永久有效。</span>
      </div>

      <transition name="invite-slide">
        <p v-if="errorMessage" class="invite-feedback invite-feedback--error">{{ errorMessage }}</p>
      </transition>
      <transition name="invite-slide">
        <p v-if="successMessage" class="invite-feedback invite-feedback--success">{{ successMessage }}</p>
      </transition>
    </section>

    <section class="invite-card">
      <div class="invite-card__header invite-card__header--split">
        <div>
          <h2 class="invite-title">邀请码列表</h2>
          <p class="invite-desc">查看邀请码状态，并进行启用、停用或删除操作。</p>
        </div>
        <button class="invite-button invite-button--secondary" :disabled="loading" @click="loadInviteCodes">
          {{ loading ? "刷新中..." : "刷新" }}
        </button>
      </div>

      <transition name="invite-fade">
        <div v-if="!loading && !hasData" class="invite-empty">
          <strong>还没有邀请码</strong>
          <p>先创建一个邀请码试试。</p>
        </div>
      </transition>

      <div v-if="hasData" class="invite-table-wrap">
        <table class="invite-table">
          <thead>
            <tr>
              <th>邀请码</th>
              <th>状态</th>
              <th>使用次数</th>
              <th>过期时间</th>
              <th>备注</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in inviteCodes" :key="item.metadata.name">
              <td>
                <div class="invite-code-cell">
                  <strong>{{ item.spec.code }}</strong>
                  <span class="invite-code-cell__meta">{{ item.metadata.name }}</span>
                </div>
              </td>
              <td>
                <span class="invite-badge" :class="statusClass(item)">
                  {{ statusText(item) }}
                </span>
              </td>
              <td>{{ usageText(item) }}</td>
              <td>{{ formatDate(item.spec.expireAt) }}</td>
              <td>{{ item.spec.note || "—" }}</td>
              <td>{{ formatDate(item.metadata.creationTimestamp) }}</td>
              <td>
                <div class="invite-row-actions">
                  <button class="invite-button invite-button--secondary" @click="toggleInviteCode(item)">
                    {{ item.spec.enabled ? "停用" : "启用" }}
                  </button>
                  <button class="invite-button invite-button--danger" @click="deleteInviteCode(item)">删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
