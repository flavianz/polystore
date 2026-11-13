import { createContext, useContext } from 'react';

export type Theme = 'dark' | 'light' | 'system';

export interface ThemeProviderState {
    theme: Theme;
    setTheme: (theme: Theme) => void;
}

export const ThemeProviderContext = createContext<ThemeProviderState>({
    theme: 'system',
    setTheme: () => null,
});

// Kept out of theme-provider.tsx so that file only exports a component —
// otherwise Vite's fast refresh cannot hot-reload the provider.
export function useTheme(): ThemeProviderState {
    return useContext(ThemeProviderContext);
}
