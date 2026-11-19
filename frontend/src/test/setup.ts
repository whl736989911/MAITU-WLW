import '@testing-library/jest-dom/vitest'
import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
if (!window.matchMedia) window.matchMedia = () => ({ matches: false, media: '', onchange: null, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => true })
class ResizeObserverStub { observe() {} unobserve() {} disconnect() {} }
if (!window.ResizeObserver) window.ResizeObserver = ResizeObserverStub
