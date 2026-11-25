import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import MessageSearch from '@/components/chat/MessageSearch.vue'
import * as chatDB from '@/db/chatDB.js'
import * as chatAPI from '@/api/chat.js'

describe('message search text rendering', () => {
  let wrapper

  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(chatDB, 'excludeRecalledSearchResults').mockImplementation(async results => results)
    vi.spyOn(chatAPI, 'searchMessages').mockResolvedValue({ code: 200, data: [] })
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  async function search(content, keyword) {
    vi.spyOn(chatDB, 'searchMessages').mockResolvedValue([
      { id: '1', sessionId: '2', content, timestamp: 1 }
    ])
    wrapper = mount(MessageSearch, { global: { plugins: [createPinia()] } })
    await wrapper.find('input').setValue(keyword)
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()
    return wrapper.find('.search-result-item__content')
  }

  it('renders attacker-controlled HTML as literal text without creating elements or handlers', async () => {
    const content = 'needle <img src="invalid" onerror="window.__auditXss=1"><svg onload="window.__auditXss=1"></svg> & end'
    const result = await search(content, 'needle')
    expect(result.text()).toBe(content)
    expect(result.find('img, svg, [onerror], [onload]').exists()).toBe(false)
    expect(result.find('mark').text()).toBe('needle')
  })

  it('highlights every case-insensitive match while preserving original text', async () => {
    const result = await search('Needle then NEEDLE', 'needle')
    expect(result.text()).toBe('Needle then NEEDLE')
    expect(result.findAll('mark').map(element => element.text())).toEqual(['Needle', 'NEEDLE'])
  })

  it('treats regular-expression metacharacters and HTML in the keyword as text', async () => {
    const result = await search('before <b>.*[x]</b> after', '<b>.*[x]</b>')
    expect(result.find('b').exists()).toBe(false)
    expect(result.find('mark').text()).toBe('<b>.*[x]</b>')
    expect(result.text()).toBe('before <b>.*[x]</b> after')
  })
})
