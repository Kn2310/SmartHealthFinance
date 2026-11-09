import type { ReactNode } from 'react'
import { ProfileFrame } from '@/features/consents/ProfileFrame'

export default function ProfileLayout({ children }: { children: ReactNode }) {
  return <ProfileFrame>{children}</ProfileFrame>
}
