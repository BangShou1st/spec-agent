import { apiClient } from '@/shared/http/client'

export interface SearchSettings {
  enabled: boolean; configured: boolean; maskedKey: string | null; revision: number
  source: 'ENVIRONMENT' | 'DATABASE'; environmentAvailable: boolean; testCode: string | null; testedAt: string | null
}
export interface EmbeddingConfig {
  provider: 'OLLAMA' | 'OPENAI_COMPATIBLE'; baseUrl: string; model: string
  timeoutSeconds: number; batchSize: number; queryStrategy: 'raw-text.v1' | 'qwen-instruct.v1'
}
export interface Probe { success: boolean; code: string; target: 'SAVED' | 'DRAFT'; dimensions?: number; testId?: string }
export interface Rebuild {
  id: string; profile_id: string; state: 'QUEUED' | 'RUNNING' | 'READY' | 'FAILED' | 'ACTIVE' | 'DISCARDED'
  processed: number; total: number; error_code: string | null
}
export interface RetrievalSettings {
  service: { config: EmbeddingConfig | null; configured: boolean; maskedKey: string | null; revision: number
    source: string; candidateProfile: string | null; dimensions: number | null; testCode: string | null; testedAt: string | null }
  index: { corpusId: string; name: string; activeModel: string | null; activeDimensions: number | null
    activeGeneration: string | null; activeProfile: string | null; readyEntries: number; jobModel: string | null; job: Rebuild | null }
}
export const services = {
  search: () => apiClient.get<SearchSettings>('/settings/search'),
  saveSearch: (body: { enabled: boolean; apiKey?: string; importEnvironment?: boolean; revision: number }) => apiClient.put<SearchSettings>('/settings/search', body),
  clearSearch: (revision: number) => apiClient.delete<SearchSettings>(`/settings/search?revision=${revision}`),
  testSearch: (apiKey?: string) => apiClient.post<Probe>('/settings/search/test', apiKey ? { apiKey } : {}),
  retrieval: () => apiClient.get<RetrievalSettings>('/settings/retrieval'),
  saveRetrieval: (body: { config: EmbeddingConfig; apiKey?: string; revision: number; testId?: string }) => apiClient.put<RetrievalSettings>('/settings/retrieval', body),
  testRetrieval: (body: { config: EmbeddingConfig; apiKey?: string; revision: number }) => apiClient.post<Probe>('/settings/retrieval/test', body),
  clearEmbeddingCredential: (revision: number) => apiClient.delete<RetrievalSettings>(`/settings/retrieval/credential?revision=${revision}`),
  rebuild: (corpusId: string, profileId: string) => apiClient.post<RetrievalSettings>('/settings/retrieval/rebuild', { corpusId, profileId }),
  retry: (id: string) => apiClient.post<RetrievalSettings>(`/settings/retrieval/rebuild/${id}/retry`),
  activate: (id: string) => apiClient.post<RetrievalSettings>(`/settings/retrieval/rebuild/${id}/activate`),
  discard: (id: string) => apiClient.post<RetrievalSettings>(`/settings/retrieval/rebuild/${id}/discard`),
}
