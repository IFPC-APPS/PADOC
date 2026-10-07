import { render, screen, waitFor } from '@testing-library/react';
import Sidebar from '@/components/Navbar';
import { SidebarProvider } from '@/lib/sidebar-context';
import { I18nProvider } from '@/lib/i18n';
import { useAuthStore } from '@/lib/store';

jest.mock('@/lib/api', () => ({
  getMe: jest.fn().mockImplementation(() => Promise.resolve(null)),
  logout: jest.fn(),
  // Par défaut tout est ouvert : les tests existants décrivent la navigation
  // complète, et c'est aussi l'état d'une installation neuve.
  getEtatFonctionnalites: jest.fn().mockImplementation(() => Promise.resolve({
    pasteurisation: true, colorimetrie: true, cuves: true,
    assistant: true, historique: true,
  })),
}));

const { getEtatFonctionnalites } = jest.requireMock('@/lib/api');

describe('Sidebar / Navbar Component', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null, isLoading: false, checkAuth: jest.fn() });
    jest.clearAllMocks();
  });

  const renderComponent = () => {
    return render(
      <I18nProvider>
        <SidebarProvider>
          <Sidebar />
        </SidebarProvider>
      </I18nProvider>
    );
  };

  test('renders login link when guest user', () => {
    renderComponent();
    expect(screen.getAllByTitle('Connexion')[0] || screen.getAllByText('Connexion')[0]).toBeInTheDocument();
  });

  test('renders user details or profile icon when logged in', () => {
    useAuthStore.setState({
      user: { firstName: 'Jean', lastName: 'Valjean', email: 'jean@ifpc.eu', role: 'USER' },
      isLoading: false,
      checkAuth: jest.fn(),
    });

    renderComponent();
    expect(screen.getByTitle('Mon profil')).toBeInTheDocument();
  });

  // ── Fonctionnalités fermées par un administrateur ─────────────────────
  //
  // On interroge les liens et non les libellés : la barre latérale est repliée
  // par défaut, et les textes ne sont alors pas rendus.

  const liens = (c: HTMLElement) =>
    Array.from(c.querySelectorAll('a')).map((a) => a.getAttribute('href'));

  test('affiche les entrées des fonctionnalités ouvertes', async () => {
    const { container } = renderComponent();
    await waitFor(() => {
      expect(liens(container)).toEqual(expect.arrayContaining(['/colorimetrie/assemblage', '/assistant']));
    });
  });

  test('retire de la navigation une fonctionnalité fermée', async () => {
    // Le cœur de la demande : un public de test ne doit pas se voir proposer
    // une porte que l'administrateur vient de fermer.
    getEtatFonctionnalites.mockImplementationOnce(() => Promise.resolve({
      pasteurisation: true, colorimetrie: false, cuves: true,
      assistant: false, historique: true,
    }));

    const { container } = renderComponent();

    await waitFor(() => {
      expect(liens(container)).not.toContain('/colorimetrie/assemblage');
    });
    expect(liens(container)).not.toContain('/assistant');
    // Ce qui reste ouvert ne bouge pas.
    expect(liens(container)).toEqual(expect.arrayContaining(['/historique', '/cuves/chai']));
  });

  test("ne retire rien si l'état est injoignable", async () => {
    // Amputer l'outil sur un incident réseau serait une panne pire que celle
    // qu'on cherche à éviter.
    getEtatFonctionnalites.mockImplementationOnce(() => Promise.reject(new Error('réseau')));

    const { container } = renderComponent();

    await waitFor(() => {
      expect(liens(container)).toEqual(expect.arrayContaining(['/colorimetrie/assemblage', '/assistant']));
    });
  });

  test('renders Admin link when user is ADMIN', () => {
    useAuthStore.setState({
      user: { firstName: 'Boss', lastName: 'Admin', email: 'admin@ifpc.eu', role: 'ADMIN' },
      isLoading: false,
      checkAuth: jest.fn(),
    });

    renderComponent();
    expect(screen.getByTitle('Admin')).toBeInTheDocument();
  });
});
