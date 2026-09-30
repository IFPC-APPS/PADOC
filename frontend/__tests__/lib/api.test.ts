import * as api from '@/lib/api';
import axios from 'axios';

jest.mock('axios', () => {
  // Annotation explicite : `create` renvoie `mAxios`, qui se référence donc
  // lui-même. Sans type déclaré, TypeScript ne peut pas l'inférer et échoue
  // en TS7022/TS7024 — ce qui rendait `tsc --noEmit` inutilisable en CI.
  const mAxios: Record<string, unknown> = {
    create: jest.fn((): Record<string, unknown> => mAxios),
    interceptors: {
      request: { use: jest.fn(), eject: jest.fn() },
      response: { use: jest.fn(), eject: jest.fn() },
    },
    get: jest.fn(),
    post: jest.fn(),
    put: jest.fn(),
    delete: jest.fn(),
  };
  return mAxios;
});

describe('API functions in lib/api.ts', () => {
  const mockedAxios = axios as unknown as jest.Mocked<typeof axios>;

  beforeEach(() => {
    localStorage.clear();
    jest.clearAllMocks();
  });

  test('login saves token and returns response data', async () => {
    const mockData = { token: 'jwt-123', user: { email: 'user@ifpc.eu' } };
    (mockedAxios.post as jest.Mock).mockResolvedValue({ data: mockData });

    const res = await api.login({ email: 'user@ifpc.eu', password: 'secret' });
    expect(res).toEqual(mockData);
    expect(localStorage.getItem('token')).toBe('jwt-123');
  });

  test('register saves token if returned and returns response data', async () => {
    const mockData = { token: 'jwt-456' };
    (mockedAxios.post as jest.Mock).mockResolvedValue({ data: mockData });

    const res = await api.register({ email: 'new@ifpc.eu', password: 'secret' });
    expect(res).toEqual(mockData);
    expect(localStorage.getItem('token')).toBe('jwt-456');
  });

  test('logout removes token from localStorage', () => {
    localStorage.setItem('token', 'jwt-123');
    api.logout();
    expect(localStorage.getItem('token')).toBeNull();
  });

  test('auth endpoints (forgotPassword, verifyResetToken, resetPassword, getMe, updateProfile, changePassword)', async () => {
    (mockedAxios.get as jest.Mock).mockResolvedValue({ data: { email: 'test@ifpc.eu' } });
    (mockedAxios.post as jest.Mock).mockResolvedValue({ data: { message: 'OK' } });
    (mockedAxios.put as jest.Mock).mockResolvedValue({ data: { success: true } });

    await expect(api.getMe()).resolves.toEqual({ email: 'test@ifpc.eu' });
    await expect(api.forgotPassword('test@ifpc.eu')).resolves.toEqual({ message: 'OK' });
    await expect(api.verifyResetToken('tok')).resolves.toEqual({ email: 'test@ifpc.eu' });
    await expect(api.resetPassword('tok', 'newpass')).resolves.toEqual({ message: 'OK' });
    await expect(api.updateProfile({ firstName: 'Jean' })).resolves.toEqual({ success: true });
    await expect(api.changePassword({ currentPassword: 'a', newPassword: 'b' })).resolves.toEqual({ success: true });
  });

  test('admin endpoints (getUsers, updateUserRole, getPendingUsers, approveUser, rejectUser, deleteUser)', async () => {
    (mockedAxios.get as jest.Mock).mockResolvedValue({ data: [] });
    (mockedAxios.put as jest.Mock).mockResolvedValue({ data: 'Role updated' });
    (mockedAxios.delete as jest.Mock).mockResolvedValue({ data: 'User deleted' });

    await expect(api.getUsers()).resolves.toEqual([]);
    await expect(api.getPendingUsers()).resolves.toEqual([]);
    await expect(api.updateUserRole(1, 'ADMIN')).resolves.toEqual('Role updated');
    await expect(api.approveUser(1)).resolves.toEqual('Role updated');
    await expect(api.rejectUser(1)).resolves.toEqual('User deleted');
    await expect(api.deleteUser(1)).resolves.toEqual('User deleted');
  });

  test('cuves and lots management endpoints', async () => {
    const cuve = { id: 1, nom: 'C1', volumeMax: 1000 };
    const lot = { id: 10, identifiant: 'LOT-1', typeProduit: 'jus_pomme', volumeActuel: 500 };

    (mockedAxios.get as jest.Mock).mockResolvedValue({ data: [cuve] });
    (mockedAxios.post as jest.Mock).mockResolvedValue({ data: cuve });
    (mockedAxios.put as jest.Mock).mockResolvedValue({ data: cuve });
    (mockedAxios.delete as jest.Mock).mockResolvedValue({ data: {} });

    await expect(api.getCuves()).resolves.toEqual([cuve]);
    await expect(api.getCuve(1)).resolves.toEqual([cuve]);
    await expect(api.createCuve(cuve)).resolves.toEqual(cuve);
    await expect(api.updateCuve(1, cuve)).resolves.toEqual(cuve);
    await expect(api.deleteCuve(1)).resolves.toBeUndefined();
    await expect(api.restoreCuve(1)).resolves.toEqual(cuve);

    await expect(api.getLots()).resolves.toEqual([cuve]);
    await expect(api.createLot(lot)).resolves.toEqual(cuve);
    await expect(api.updateLot(10, { volumeActuel: 600 })).resolves.toEqual(cuve);
    await expect(api.deleteLot(10)).resolves.toBeUndefined();
    await expect(api.restoreLot(10)).resolves.toEqual(cuve);
  });

  test('operations métier endpoints (opNettoyage, opRemplissage, opTransfert, opTransformation, opAssemblage)', async () => {
    const op = { id: 1, type: 'NETTOYAGE' };
    (mockedAxios.post as jest.Mock).mockResolvedValue({ data: op });

    await expect(api.opNettoyage(1)).resolves.toEqual(op);
    await expect(api.opRemplissage(1, 10, 500)).resolves.toEqual(op);
    await expect(api.opTransfert(1, 2, 10, 500)).resolves.toEqual(op);
    await expect(api.opTransformation({ lotId: 10, colorHex: '#FFF' })).resolves.toEqual(op);
    await expect(api.opAssemblage({ sources: [{ cuveId: 1, lotId: 10, volume: 500 }], cuveDestId: 2, newLotIdentifiant: 'LOT-NEW', typeProduit: 'jus_pomme' })).resolves.toEqual(op);
  });

  test('evaluerPasteurisation & proposerBareme', async () => {
    (mockedAxios.post as jest.Mock).mockResolvedValue({ data: { conforme: true } });

    const res = await api.evaluerPasteurisation({ temperatures: [60, 70], temps: [0, 10], product_type: 'jus_pomme', unite_temps: 'minute' });
    expect(res).toEqual({ conforme: true });

    const baremeRes = await api.proposerBareme({ product_type: 'jus_pomme', procede: 'flash' });
    expect(baremeRes).toEqual({ conforme: true });
  });
});
