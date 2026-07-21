import { createApp } from 'vue'
import App from './App.vue'
import { configureUnauthorizedHandler } from './api'
import router from './router'
import { createUnauthorizedHandler } from './session'
import store from './store'
import './styles.css'

configureUnauthorizedHandler(createUnauthorizedHandler(store, router))

createApp(App).use(store).use(router).mount('#app')
