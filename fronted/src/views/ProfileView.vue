<template>
  <div class="profile-page">
    <div class="pg-grid">
      <aside class="pg-side">
        <div class="page-card card-me">
          <el-upload
            class="avatar-uploader"
            action="/api/upload"
            :show-file-list="false"
            :headers="uploadHeaders"
            :on-success="handleAvatarSuccess"
            :before-upload="beforeAvatarUpload">
            <img v-if="profile.avatar" :src="profile.avatar" class="avatar-img" />
            <div v-else class="avatar-placeholder">上传头像</div>
          </el-upload>
          <h3 class="me-name">{{ profile.nickname || profile.username || '用户' }}</h3>
          <p class="me-sign">{{ profile.signature || '还没有个性签名' }}</p>
          <p class="avatar-tip">点击头像更换（jpg/png，不超过 5MB）</p>
        </div>

        <div class="page-card">
          <h2>学习进度</h2>
          <div class="study-top">
            <el-progress
              type="circle"
              :width="96"
              :percentage="study.percent || 0"
              :color="progressColor" />
            <div class="study-num">
              <p class="sn-main"><b>{{ study.readCount || 0 }}</b> / {{ study.total || 0 }} 篇</p>
              <p class="sn-tip">已读 / 知识库已发布总数</p>
            </div>
          </div>
        </div>
      </aside>

      <main class="pg-main">
        <div class="page-card">
          <h2>个人资料</h2>
          <el-form label-width="90px" class="profile-form">
            <el-form-item label="用户名">
              <el-input :model-value="profile.username" disabled />
            </el-form-item>
            <el-form-item label="昵称">
              <el-input v-model="profile.nickname" />
            </el-form-item>
            <el-form-item label="手机号">
              <el-input v-model="profile.phone" placeholder="请输入手机号" />
            </el-form-item>
            <el-form-item label="性别">
              <el-radio-group v-model="profile.gender">
                <el-radio label="男">男</el-radio>
                <el-radio label="女">女</el-radio>
                <el-radio label="保密">保密</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="年龄">
              <el-input-number v-model="profile.age" :min="18" :max="100" />
            </el-form-item>
            <el-form-item label="个性签名">
              <el-input v-model="profile.signature" type="textarea" :rows="2" />
            </el-form-item>
            <el-form-item>
              <button class="save-btn" @click="saveProfile()">保存修改</button>
            </el-form-item>
          </el-form>
        </div>

        <div class="page-card">
          <h2>最近阅读</h2>
          <div class="study-recent" v-if="study.recent?.length">
            <div
              v-for="item in study.recent"
              :key="item.articleId"
              class="sr-item"
              @click="goArticle(item.articleId)">
              <span class="sr-title">{{ item.title }}</span>
              <span class="sr-cat" v-if="item.category">{{ item.category }}</span>
              <span class="sr-time">{{ shortTime(item.lastReadAt) }}</span>
            </div>
          </div>
          <p class="study-empty" v-else>还没有阅读记录，去知识库看看吧</p>
        </div>

        <div class="page-card">
          <h2>修改密码</h2>
          <el-form label-width="90px">
            <el-form-item label="旧密码">
              <el-input
                v-model="passwordForm.oldPassword"
                type="password"
                show-password />
            </el-form-item>
            <el-form-item label="新密码">
              <el-input
                v-model="passwordForm.newPassword"
                type="password"
                show-password />
            </el-form-item>
            <el-form-item>
              <button class="save-btn warning" @click="changePassword">
                修改密码
              </button>
            </el-form-item>
          </el-form>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import axios from 'axios'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getStudyStats } from '@/api/frontend'

const router = useRouter()
const profile = ref({})
const passwordForm = ref({ oldPassword: '', newPassword: '' })
const study = ref({})

const uploadHeaders = { Authorization: 'Bearer ' + (sessionStorage.getItem('token') || '') }

const handleAvatarSuccess = async (res) => {
  if (res?.errno !== 0 || !res.data?.url) {
    ElMessage.error(res?.msg || '上传失败')
    return
  }
  profile.value.avatar = res.data.url
  // 上传只是把文件放到服务器，不写库；不顺手保存的话刷新一下头像又变回去了
  await saveProfile('头像已更新')
}

const beforeAvatarUpload = (file) => {
  const isImg = file.type.startsWith('image/')
  if (!isImg) ElMessage.error('请选择图片文件')
  return isImg
}

const loadProfile = async () => {
  try {
    const res = await axios.get('/api/user/profile')
    profile.value = res.data
  } catch (e) {
    ElMessage.error(errText(e, '加载个人资料失败'))
  }
}

// 全局 axios 拦截器只处理 401，其它错误一律静默；不自己提示的话保存失败像是什么都没发生
const errText = (e, fallback) => e?.response?.data?.message || e?.message || fallback

// 顶栏的头像和昵称是从 sessionStorage 读的，保存完必须一起同步，否则要重新登录才变
const syncSession = () => {
  sessionStorage.setItem('nickname', profile.value.nickname || '')
  const raw = sessionStorage.getItem('userInfo')
  if (!raw) return
  try {
    const info = JSON.parse(raw)
    info.nickname = profile.value.nickname
    info.avatar = profile.value.avatar || ''
    sessionStorage.setItem('userInfo', JSON.stringify(info))
  } catch (e) {
    console.error('同步本地用户信息失败:', e)
  }
}

const saveProfile = async (message = '保存成功') => {
  try {
    await axios.put('/api/user/profile', {
      nickname: profile.value.nickname,
      gender: profile.value.gender,
      age: profile.value.age,
      signature: profile.value.signature,
      phone: profile.value.phone,
      avatar: profile.value.avatar,
    })
    syncSession()
    ElMessage.success(message)
  } catch (e) {
    ElMessage.error(errText(e, '保存失败'))
  }
}

const changePassword = async () => {
  if (!passwordForm.value.oldPassword || !passwordForm.value.newPassword) {
    ElMessage.warning('请输入旧密码和新密码')
    return
  }
  try {
    await axios.put('/api/user/password', {
      oldPassword: passwordForm.value.oldPassword,
      newPassword: passwordForm.value.newPassword,
    })
    ElMessage.success('密码修改成功')
    passwordForm.value = { oldPassword: '', newPassword: '' }
  } catch (e) {
    ElMessage.error(errText(e, '密码修改失败'))
  }
}

// 进度低的时候用暖色提醒，读完一半以上转绿
const progressColor = (percent) => {
  if (percent >= 60) return '#67c23a'
  if (percent >= 30) return '#409eff'
  return '#e6a23c'
}

const loadStudy = async () => {
  try {
    study.value = (await getStudyStats()) || {}
  } catch (e) {
    console.error('加载学习进度失败:', e)
  }
}

const goArticle = (id) => {
  if (id) router.push(`/knowledge/${id}`)
}

const shortTime = (v) => (v ? String(v).replace('T', ' ').slice(5, 16) : '')

onMounted(() => {
  loadProfile()
  loadStudy()
})
</script>

<style scoped>
.profile-page {
  max-width: 1000px;
  margin: 0 auto;
}
/* 左窄右宽：头像和进度这种一眼看完的信息不该占满整行 */
.pg-grid {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: 18px;
  align-items: start;
}
.pg-side,
.pg-main {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}
.page-card {
  background: #fff;
  border: 1px solid rgba(64, 158, 255, 0.12);
  border-radius: 16px;
  box-shadow: 0 6px 20px rgba(64, 158, 255, 0.07);
  padding: 24px;
}
h2 {
  font-size: 17px;
  color: #333;
  margin: 0 0 18px;
}
.card-me {
  text-align: center;
  background: linear-gradient(135deg, rgba(64, 158, 255, 0.08), rgba(103, 194, 58, 0.05));
}
.me-name {
  margin: 12px 0 0;
  font-size: 17px;
  font-weight: 700;
  color: #333;
}
.me-sign {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: #666;
}
.avatar-uploader {
  display: inline-block;
}
.avatar-img {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  object-fit: cover;
  border: 3px solid #fff;
  box-shadow: 0 4px 14px rgba(64, 158, 255, 0.18);
  cursor: pointer;
}
.avatar-placeholder {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  border: 2px dashed rgba(64, 158, 255, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #409eff;
  font-size: 13px;
  cursor: pointer;
  background: rgba(255, 255, 255, 0.6);
}
.avatar-tip {
  color: #999;
  font-size: 12px;
  margin: 10px 0 0;
}
.profile-form {
  margin-bottom: 0;
}
.save-btn {
  padding: 10px 28px;
  border: none;
  border-radius: 10px;
  background: linear-gradient(135deg, #409eff, #67c23a);
  color: white;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.3s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.3s ease;
}
.save-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(64, 158, 255, 0.35);
}
.save-btn.warning {
  background: linear-gradient(135deg, #e6a23c, #f56c6c);
}
.save-btn.warning:hover {
  box-shadow: 0 6px 16px rgba(230, 162, 60, 0.35);
}
/* 侧栏只有 300px，环形和数字竖排才不挤 */
.study-top {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.study-num {
  text-align: center;
}
.sn-main {
  font-size: 15px;
  color: #666;
  margin: 0 0 6px;
}
.sn-main b {
  font-size: 26px;
  font-weight: 800;
  color: #409eff;
  margin-right: 2px;
}
.sn-tip {
  font-size: 12px;
  color: #999;
  margin: 0;
}
.sr-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.3s cubic-bezier(0.22, 1, 0.36, 1);
}
.sr-item:hover {
  background: rgba(64, 158, 255, 0.06);
}
.sr-item:hover .sr-title {
  color: #409eff;
}
.sr-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.3s ease;
}
.sr-cat {
  flex-shrink: 0;
  font-size: 12px;
  color: #67c23a;
  background: rgba(103, 194, 58, 0.1);
  border: 1px solid rgba(103, 194, 58, 0.2);
  border-radius: 6px;
  padding: 2px 9px;
}
.sr-time {
  flex-shrink: 0;
  font-size: 12px;
  color: #999;
}
.study-empty {
  margin: 0;
  font-size: 13px;
  color: #999;
}
/* 窄屏两列摞成一列，头像卡在最上面 */
@media (max-width: 900px) {
  .pg-grid {
    grid-template-columns: 1fr;
  }
}
</style>
