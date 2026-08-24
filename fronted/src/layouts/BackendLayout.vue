<template>
  <div class="backend-layout">
    <el-container>
      <el-aside width="210px">
        <div class="logo">
          <el-icon size="22"><Monitor /></el-icon>
          <span>管理后台</span>
        </div>
        <el-menu
          :default-active="$route.path"
          :router="true"
          class="sidebar-menu"
        >
          <el-menu-item index="/admin/dashboard">
            <el-icon><DataLine /></el-icon>
            <span>数据看板</span>
          </el-menu-item>
          <el-menu-item index="/admin/users">
            <el-icon><User /></el-icon>
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/articles">
            <el-icon><Document /></el-icon>
            <span>文章管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/content-audit">
            <el-icon><ChatDotRound /></el-icon>
            <span>内容审核</span>
          </el-menu-item>
        </el-menu>
        <div class="aside-foot" @click="router.push('/')">
          <el-icon><Back /></el-icon>
          <span>返回前台</span>
        </div>
      </el-aside>

      <el-container>
        <el-header height="60px">
          <div class="header-content">
            <div class="header-title">
              {{ route.meta.title || '管理后台' }}
              <span class="header-sub">{{ subtitle }}</span>
            </div>
            <div class="user-actions">
              <el-dropdown @command="handleCommand">
                <span class="user-info">
                  <el-avatar :size="30">{{ nickname.slice(0, 1) }}</el-avatar>
                  <span class="nickname">{{ nickname }}</span>
                  <el-icon size="12"><ArrowDown /></el-icon>
                </span>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="front">返回前台</el-dropdown-item>
                    <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
        </el-header>

        <el-main>
          <router-view />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Monitor, DataLine, User, Document, Back, ArrowDown, ChatDotRound, Briefcase } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()
const nickname = ref('管理员')

const SUBTITLES = {
  '/admin/dashboard': '平台整体运营数据',
  '/admin/users': '账号、角色与状态维护',
  '/admin/articles': '知识库内容的创建与上下架',
  '/admin/content-audit': '投资日记、问答记录、模拟组合审核'
}
const subtitle = computed(() => SUBTITLES[route.path] || '')

onMounted(() => {
  const userInfo = sessionStorage.getItem('userInfo')
  if (userInfo) {
    const user = JSON.parse(userInfo)
    nickname.value = user.nickname || user.username || '管理员'
  }
})

const handleCommand = (command) => {
  switch (command) {
    case 'front':
      router.push('/')
      break
    case 'logout':
      ElMessageBox.confirm('确定要退出登录吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        sessionStorage.clear()
        router.push('/auth/login')
      })
      break
  }
}
</script>

<style scoped lang="scss">
$brand: #409eff;
$green: #67c23a;

.backend-layout {
  .el-container {
    height: 100vh;
  }

  .el-aside {
    position: relative;
    background: linear-gradient(180deg, #071b34, #04101f);
    color: #fff;
    display: flex;
    flex-direction: column;

    .logo {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 0 20px;
      height: 60px;
      font-size: 17px;
      font-weight: 700;
      letter-spacing: 0.5px;
      color: #fff;
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);

      .el-icon {
        color: $brand;
      }
    }

    .sidebar-menu {
      flex: 1;
      border: none;
      background-color: transparent;
      padding: 14px 10px;

      .el-menu-item {
        height: 46px;
        margin-bottom: 6px;
        border-radius: 10px;
        color: rgba(255, 255, 255, 0.7);
        transition: all 0.3s cubic-bezier(0.22, 1, 0.36, 1);

        &:hover {
          color: #fff;
          background-color: rgba(64, 158, 255, 0.14);
        }

        &.is-active {
          color: #fff;
          font-weight: 600;
          background: linear-gradient(94deg, rgba(64, 158, 255, 0.9), rgba(103, 194, 58, 0.55));
          box-shadow: 0 6px 16px rgba(64, 158, 255, 0.28);
        }
      }
    }

    // 管理员经常要回前台核对改动效果，钉在侧栏底部比藏在下拉里顺手
    .aside-foot {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 14px 22px;
      font-size: 13px;
      cursor: pointer;
      color: rgba(255, 255, 255, 0.55);
      border-top: 1px solid rgba(255, 255, 255, 0.08);
      transition: color 0.3s;

      &:hover {
        color: #fff;
      }
    }
  }

  .el-header {
    background-color: #fff;
    border-bottom: 1px solid #e9edf2;
    padding: 0;

    .header-content {
      display: flex;
      align-items: center;
      justify-content: space-between;
      height: 100%;
      padding: 0 24px;

      .header-title {
        display: flex;
        align-items: baseline;
        gap: 10px;
        font-size: 17px;
        font-weight: 600;
        color: #1f2329;

        .header-sub {
          font-size: 12.5px;
          font-weight: 400;
          color: #a8adb7;
        }
      }

      .user-actions {
        .user-info {
          display: flex;
          align-items: center;
          gap: 8px;
          cursor: pointer;
          padding: 5px 10px;
          border-radius: 20px;
          outline: none;
          transition: background-color 0.3s;

          &:hover {
            background-color: rgba(64, 158, 255, 0.08);
          }

          :deep(.el-avatar) {
            background: linear-gradient(135deg, $brand, $green);
            font-size: 13px;
          }

          .nickname {
            font-size: 13.5px;
            color: #606266;
          }

          .el-icon {
            color: #b6bac1;
          }
        }
      }
    }
  }

  .el-main {
    background-color: #f4f6fa;
    padding: 22px 24px 32px;
  }
}
</style>