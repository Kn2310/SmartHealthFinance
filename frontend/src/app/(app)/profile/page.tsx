import { redirect } from 'next/navigation'

/** O Perfil só tem a seção Consentimentos por enquanto (ADR-0038 §9). */
export default function ProfilePage() {
  redirect('/profile/consents')
}
