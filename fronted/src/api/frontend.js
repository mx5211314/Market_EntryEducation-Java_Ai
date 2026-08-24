import request from '@/utils/request'
import publicRequest from '@/utils/publicRequest'

// 用户相关接口
export const login = (data) => {
  return request.post('/auth/login', data)
}

export const register = (data) => {
  return request.post('/auth/register', data)
}

export const getCurrentUser = () => {
  return request.get('/auth/me')
}

// 聊天相关接口
export const startSession = (data) => {
  return request.post('/session/create', data)
}

export const getSessionList = (params) => {
  return request.get('/chat', { params })
}

export const deleteSession = (sessionId) => {
  return request.delete(`/session/${sessionId}`)
}

export const renameSession = (sessionId, title) => {
  return request.put(`/session/${sessionId}/title`, { title })
}

export const getSessionDetail = (sessionId) => {
  return request.get(`/session/${sessionId}/messages`)
}

export const sendMessageStream = (data) => {
  return fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer ' + sessionStorage.getItem('token'),
    },
    body: JSON.stringify(data),
  })
}

// 情绪分析接口
export const getSessionEmotion = (sessionId) => {
  return request.get(`/chat/analyze-session/${sessionId}`)
}

// 文章相关接口 - 使用公开请求（无需登录）
export const getKnowledgeList = (params) => {
  return publicRequest.get('/user/article/list', { params })
}

export const getStudyStats = () => {
  return request.get('/user/study/stats')
}

export const getKnowledgeDetail = (articleId) => {
  return publicRequest.get(`/user/article/${articleId}`)
}

export const getRecommendList = (params) => {
  return publicRequest.get('/user/article/list', { params })
}

export const getArticleCategories = () => {
  return publicRequest.get('/user/article/categories')
}

// 收藏接口
export const getFavoriteList = (params) => {
  return request.get('/user/favorite/list', { params })
}

export const checkFavorite = (articleId) => {
  return request.get(`/user/favorite/check/${articleId}`)
}

export const addFavorite = (articleId) => {
  return request.post(`/user/favorite/${articleId}`)
}

export const removeFavorite = (articleId) => {
  return request.delete(`/user/favorite/${articleId}`)
}

// 风险测评接口
export const getAssessmentQuestions = () => {
  return request.get('/user/assessment/questions')
}

export const submitAssessment = (answers, agreed = true) => {
  // agreed 是风险揭示书的签署确认，后端收不到就不出具报告
  return request.post('/user/assessment/submit', { answers, agreed })
}

export const getAssessmentLatest = () => {
  return request.get('/user/assessment/latest')
}

export const getAssessmentHistory = (params) => {
  return request.get('/user/assessment/history', { params })
}

// 模拟引导接口
export const getSimProducts = () => {
  return request.get('/user/simulation/products')
}

export const getSimProfile = () => {
  return request.get('/user/simulation/profile')
}

export const analyzeSimPortfolio = (holdings, amount) => {
  return request.post('/user/simulation/analyze', { holdings, amount })
}

export const saveSimPortfolio = (name, holdings, amount) => {
  return request.post('/user/simulation/portfolio', { name, holdings, amount })
}

export const getSimPortfolios = () => {
  return request.get('/user/simulation/portfolio')
}

export const deleteSimPortfolio = (id) => {
  return request.delete(`/user/simulation/portfolio/${id}`)
}

// 投资日记接口
export const getDiaryList = (params) => {
  return request.get('/user/diary/list', { params })
}

export const getDiaryStats = () => {
  return request.get('/user/diary/stats')
}

export const getDiaryPending = () => {
  return request.get('/user/diary/pending')
}

export const getDiaryOptions = () => {
  return request.get('/user/diary/options')
}

export const getDiaryDetail = (id) => {
  return request.get(`/user/diary/${id}`)
}

export const createDiary = (data) => {
  return request.post('/user/diary', data)
}

export const updateDiary = (id, data) => {
  return request.put(`/user/diary/${id}`, data)
}

export const removeDiary = (id) => {
  return request.delete(`/user/diary/${id}`)
}

export const reviewDiary = (id, data) => {
  return request.post(`/user/diary/${id}/review`, data)
}

export const coachDiary = (id) => {
  return request.post(`/user/diary/${id}/coach`)
}

