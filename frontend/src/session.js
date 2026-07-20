export function resolveSiteId(sites, preferredSiteId) {
  if (!Array.isArray(sites) || sites.length === 0) return null
  const availableIds = sites.map(site => Number(site.id)).filter(Number.isFinite)
  const preferred = Number(preferredSiteId)
  return availableIds.includes(preferred) ? preferred : (availableIds[0] ?? null)
}

export function createUnauthorizedHandler(store, router) {
  return () => {
    const currentRoute = router.currentRoute.value
    store.commit('clearSession')
    store.dispatch('disconnectRealtime')
    if (currentRoute.path !== '/login') {
      void router.replace({ path: '/login', query: { redirect: currentRoute.fullPath } })
    }
  }
}
