import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import MessageSearch from '@/components/chat/MessageSearch.vue'
import { useSessionStore } from '@/stores/session.js'
import * as chatDB from '@/db/chatDB.js'
import http from '@/api/http.js'
import { useChatStore } from '@/stores/chat.js'
import fixture from '../../s-server/src/test/resources/contracts/message-search-response.json'

vi.mock('@/api/provider.js', () => ({ USE_MOCK: false }))

describe('serialized remote message search contract', () => {
  const originalAdapter = http.defaults.adapter
  let wrapper
  let adapter

  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(chatDB, 'excludeRecalledSearchResults').mockImplementation(async results => results)
    vi.spyOn(chatDB, 'searchMessages').mockResolvedValue([])
    adapter = vi.fn(async config => ({ data: JSON.stringify(fixture), status: 200, statusText: 'OK', headers: {}, config }))
    http.defaults.adapter = adapter
    const pinia = createPinia()
    useSessionStore(pinia).sessions = [{ id: '2', targetName: 'Private' }, { id: 'group_10', targetName: 'Group' }]
    wrapper = mount(MessageSearch, { global: { plugins: [pinia] } })
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.restoreAllMocks()
    vi.useRealTimers()
    http.defaults.adapter = originalAdapter
  })

  async function search(keyword = 'needle') {
    await wrapper.find('input').setValue(keyword)
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()
  }

  it('renders the actual backend data array through the real API adapter without losing IDs or executing HTML', async () => {
    await search()
    expect(adapter).toHaveBeenCalledOnce()
    expect(adapter.mock.calls[0][0]).toMatchObject({ url: '/chat/search', params: { keyword: 'needle', limit: 50 } })
    const results = wrapper.findAll('.search-result-item')
    expect(results).toHaveLength(2)
    expect(results[0].find('.search-result-item__content').text()).toBe(fixture.data[0].content)
    expect(results[0].find('.search-result-item__session').text()).toBe('Group')
    expect(results[0].find('.search-result-item__time').text()).not.toContain('NaN')
    expect(results[0].find('img, [onerror]').exists()).toBe(false)
    await results[0].trigger('click')
    expect(wrapper.emitted('navigate')[0][0]).toMatchObject({
      messageId: '9007199254740995', fromId: '9007199254740997', sessionId: 'group_10'
    })
  })

  it('merges by session and message ID while preferring local content and sorting numeric timestamps', async () => {
    vi.mocked(chatDB.searchMessages).mockResolvedValue([
      { id: '9007199254740993', sessionId: '2', content: 'needle local', timestamp: 1700000003000 }
    ])
    await search()
    const results = wrapper.findAll('.search-result-item__content')
    expect(results.map(result => result.text())).toEqual(['needle local', fixture.data[0].content])
  })

  it('keeps local results when the remote request fails', async () => {
    vi.mocked(chatDB.searchMessages).mockResolvedValue([
      { id: '1', sessionId: '2', content: 'needle local', timestamp: 1 }
    ])
    adapter.mockRejectedValue(new Error('offline'))
    await search()
    expect(wrapper.find('.search-result-item__content').text()).toBe('needle local')
  })

  it('deduplicates remote retries by session and ID without dropping another session with the same ID', async () => {
    vi.mocked(chatDB.searchMessages).mockResolvedValue([
      { id: '42', sessionId: '2', content: 'needle local', timestamp: 1 }
    ])
    adapter.mockImplementation(async config => ({
      data: JSON.stringify({ ...fixture, data: [
        { ...fixture.data[0], messageId: '42' },
        { ...fixture.data[0], messageId: '42' },
        { ...fixture.data[1], messageId: 42 }
      ] }), status: 200, statusText: 'OK', headers: {}, config
    }))
    await search()
    expect(wrapper.findAll('.search-result-item__content').map(result => result.text()))
      .toEqual([fixture.data[0].content, 'needle local'])
  })

  it('does not let a late response overwrite a newer keyword search', async () => {
    let finishFirst
    adapter.mockImplementationOnce(config => new Promise(resolve => {
      finishFirst = () => resolve({ data: JSON.stringify(fixture), status: 200, headers: {}, config })
    }))
    await search('first')
    expect(wrapper.text()).toContain('搜索中')
    adapter.mockImplementationOnce(async config => ({
      data: JSON.stringify({ ...fixture, data: [{ ...fixture.data[1], content: 'second result' }] }),
      status: 200, headers: {}, config
    }))
    await search('second')
    expect(wrapper.find('.search-result-item__content').text()).toBe('second result')
    finishFirst()
    await flushPromises()
    expect(wrapper.find('.search-result-item__content').text()).toBe('second result')
    expect(wrapper.text()).not.toContain('搜索中')
  })

  it('does not resurrect results or loading state after clearing an in-flight search', async () => {
    let finish
    adapter.mockImplementationOnce(config => new Promise(resolve => {
      finish = () => resolve({ data: JSON.stringify(fixture), status: 200, headers: {}, config })
    }))
    await search()
    wrapper.vm.clearSearch()
    finish()
    await flushPromises()
    expect(wrapper.findAll('.search-result-item')).toHaveLength(0)
    expect(wrapper.find('.message-search__status').exists()).toBe(false)
    expect(wrapper.find('input').element.value).toBe('')
  })

  it('cancels the debounce when the component is unmounted', async () => {
    await wrapper.find('input').setValue('needle')
    wrapper.unmount()
    wrapper = null
    await vi.advanceTimersByTimeAsync(300)
    expect(chatDB.searchMessages).not.toHaveBeenCalled()
    expect(adapter).not.toHaveBeenCalled()
  })

  it('does not request remote results when the local page is already full', async () => {
    vi.mocked(chatDB.searchMessages).mockResolvedValue(Array.from({ length: 50 }, (_, index) => ({
      id: String(index), sessionId: '2', content: 'needle local', timestamp: index
    })))
    await search()
    expect(adapter).not.toHaveBeenCalled()
    expect(wrapper.findAll('.search-result-item')).toHaveLength(50)
  })

  it('removes an already-rendered result when its recall is applied', async () => {
    await search()
    useChatStore().applyRecallProjection('group_10', { id: '9007199254740995', recalled: true })
    await flushPromises()
    expect(wrapper.findAll('.search-result-item__content').map(result => result.text())).toEqual(['needle private'])
  })

  it('does not publish an in-flight result recalled before the response arrived', async () => {
    let finish
    adapter.mockImplementationOnce(config => new Promise(resolve => {
      finish = () => resolve({ data: JSON.stringify(fixture), status: 200, headers: {}, config })
    }))
    await search()
    useChatStore().applyRecallProjection('group_10', { id: '9007199254740995', recalled: true })
    finish()
    await flushPromises()
    expect(wrapper.findAll('.search-result-item__content').map(result => result.text())).toEqual(['needle private'])
  })
})
