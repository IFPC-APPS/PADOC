"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuthStore } from "@/lib/store";
import {
  getUsers, updateUserRole, getPendingUsers, approveUser, rejectUser, deleteUser,
} from "@/lib/api";
import { Loader2, Search, X, Check, Activity, ShieldCheck, Trash2 } from "lucide-react";
import { useI18n } from "@/lib/i18n";
import PanneauFonctionnalites from "@/components/PanneauFonctionnalites";

interface UserData {
  id: number;
  firstName: string;
  lastName: string;
  companyName?: string | null;
  companyRole?: string | null;
  email: string;
  role: string;
  enabled: boolean;
  lastLogin: string | null;
}


type Tab = "pending" | "users" | "fonctionnalites";

function formatRelativeTime(iso: string | null, t: any): string {
  if (!iso) return t("admin.never");
  const d = new Date(iso);
  const diffMin = Math.floor((new Date().getTime() - d.getTime()) / 60000);
  if (diffMin < 1) return t("admin.justNow");
  if (diffMin < 60) return t("admin.minutesAgo", { n: diffMin });
  const diffH = Math.floor(diffMin / 60);
  if (diffH < 24) return t("admin.hoursAgo", { n: diffH });
  const diffD = Math.floor(diffH / 24);
  if (diffD < 7) return t("admin.daysAgo", { n: diffD });
  return d.toLocaleDateString(undefined, { day: "2-digit", month: "short", year: "numeric" });
}

export default function AdminPage() {
  const router = useRouter();
  const { user, isLoading } = useAuthStore();
  const { t } = useI18n();
  const [activeTab, setActiveTab] = useState<Tab>("pending");

  const [users, setUsers] = useState<UserData[]>([]);
  const [pendingUsers, setPendingUsers] = useState<UserData[]>([]);
  
  const [isFetching, setIsFetching] = useState(true);
  const [search, setSearch] = useState("");
  const [selectedUserId, setSelectedUserId] = useState<number | null>(null);
  
  const [processingId, setProcessingId] = useState<number | null>(null);

  const isAdmin = user?.role === "ADMIN";

  useEffect(() => {
    if (!isLoading && !isAdmin) router.push("/");
  }, [isLoading, isAdmin, router]);

  useEffect(() => {
    if (!isAdmin) return;
    setIsFetching(true);
    
    Promise.all([
      getUsers().catch(() => []),
      getPendingUsers().catch(() => []),
    ]).then(([uData, pData]) => {
      setUsers(uData.filter((u: UserData) => u.role !== "ADMIN"));
      setPendingUsers(pData);
    }).finally(() => setIsFetching(false));
  }, [isAdmin]);

  const handleRoleChange = async (userId: number, newRole: string) => {
    setProcessingId(userId);
    try {
      await updateUserRole(userId, newRole);
      setUsers(prev => prev.map(u => u.id === userId ? { ...u, role: newRole } : u));
    } catch { 
      alert(t("admin.updateError")); 
    } finally { 
      setProcessingId(null); 
    }
  };

  const handleApprove = async (userId: number) => {
    setProcessingId(userId);
    try {
      await approveUser(userId);
      const approvedUser = pendingUsers.find(u => u.id === userId);
      setPendingUsers(prev => prev.filter(u => u.id !== userId));
      if (approvedUser) setUsers(prev => [approvedUser, ...prev]);
    } catch { 
      alert(t("admin.approveError")); 
    } finally { 
      setProcessingId(null); 
    }
  };

  const handleReject = async (userId: number) => {
    if (!confirm(t("admin.confirmReject"))) return;
    setProcessingId(userId);
    try {
      await rejectUser(userId);
      setPendingUsers(prev => prev.filter(u => u.id !== userId));
    } catch { 
      alert(t("admin.rejectError")); 
    } finally { 
      setProcessingId(null); 
    }
  };

  const handleDeleteUser = async (userId: number, name: string) => {
    if (!confirm(t("admin.confirmDeleteUser", { name }))) return;
    setProcessingId(userId);
    try {
      await deleteUser(userId);
      setUsers(prev => prev.filter(u => u.id !== userId));
      if (selectedUserId === userId) setSelectedUserId(null);
    } catch { 
      alert(t("admin.deleteError")); 
    } finally { 
      setProcessingId(null); 
    }
  };


  const filteredUsers = users.filter(u =>
    u.email.toLowerCase().includes(search.toLowerCase()) ||
    u.firstName.toLowerCase().includes(search.toLowerCase()) ||
    u.lastName.toLowerCase().includes(search.toLowerCase())
  );
  
  const selectedUser = users.find(u => u.id === selectedUserId) ?? null;

  if (isLoading || isFetching) {
    return <div className="h-screen flex items-center justify-center bg-brand-gray"><Loader2 className="w-6 h-6 animate-spin text-gray-300" /></div>;
  }
  if (!isAdmin) return null;

  return (
    <div className="min-h-screen bg-[#F8F9FA] text-gray-900 font-sans">
      <div className="max-w-6xl mx-auto px-6 py-10 sm:py-16">
        
        {/* --- HEADER & KPIs --- */}
        <header className="mb-10 flex flex-col md:flex-row md:items-end justify-between gap-6">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">{t("admin.title")}</h1>
            <p className="text-sm text-gray-500 mt-1 font-medium">{t("admin.subtitle")}</p>
          </div>
          <div className="flex items-center gap-4">
            <div className="bg-white border border-gray-200/60 rounded-xl px-4 py-3 shadow-sm flex flex-col min-w-[120px]">
              <span className="text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1">Total Utilisateurs</span>
              <span className="text-xl font-bold">{users.length}</span>
            </div>
            <div className="bg-white border border-gray-200/60 rounded-xl px-4 py-3 shadow-sm flex flex-col min-w-[120px]">
              <span className="text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1">Experts</span>
              <span className="text-xl font-bold text-brand-primary">{users.filter(u => u.role === 'EXPERT').length}</span>
            </div>
            {pendingUsers.length > 0 && (
              <div className="bg-orange-50 border border-orange-100 rounded-xl px-4 py-3 shadow-sm flex flex-col min-w-[120px]">
                <span className="text-[10px] font-bold text-orange-600/70 uppercase tracking-wider mb-1">En attente</span>
                <span className="text-xl font-bold text-orange-600">{pendingUsers.length}</span>
              </div>
            )}
          </div>
        </header>

        {/* --- NAVIGATION TABS --- */}
        <div className="flex gap-6 border-b border-gray-200 mb-8 overflow-x-auto no-scrollbar">
          {[
            { id: "pending", label: t("admin.tabRequests"), count: pendingUsers.length },
            { id: "users", label: t("admin.tabUsers") },
            { id: "fonctionnalites", label: "Fonctionnalités" },
          ].map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as Tab)}
              className={`pb-3 text-sm font-semibold transition-colors flex items-center gap-2 relative whitespace-nowrap ${
                activeTab === tab.id ? "text-gray-900" : "text-gray-400 hover:text-gray-600"
              }`}
            >
              {tab.label}
              {tab.count !== undefined && tab.count > 0 && (
                <span className="px-1.5 py-0.5 bg-gray-100 text-gray-600 text-[10px] rounded-full leading-none">{tab.count}</span>
              )}
              {activeTab === tab.id && (
                <span className="absolute bottom-0 left-0 w-full h-[2px] bg-gray-900 rounded-t-full" />
              )}
            </button>
          ))}
        </div>

        {/* --- TAB CONTENT --- */}

        {activeTab === "fonctionnalites" && <PanneauFonctionnalites />}
        
        {/* PENDING USERS */}
        {activeTab === "pending" && (
          <div className="animate-in fade-in duration-300">
            {pendingUsers.length === 0 ? (
              <div className="py-20 text-center">
                <ShieldCheck className="w-12 h-12 text-gray-200 mx-auto mb-4" />
                <h3 className="text-sm font-medium text-gray-500">{t("admin.noPending")}</h3>
              </div>
            ) : (
              <div className="grid gap-3">
                {pendingUsers.map(u => (
                  <div key={u.id} className="bg-white border border-gray-200/60 rounded-2xl p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-4 shadow-sm hover:shadow-md transition-shadow">
                    <div className="flex items-center gap-4">
                      <div className="w-10 h-10 rounded-full bg-orange-50 flex items-center justify-center text-orange-600 font-bold text-sm shrink-0">
                        {u.firstName.charAt(0)}{u.lastName.charAt(0)}
                      </div>
                      <div>
                        <h4 className="font-semibold text-gray-900">{u.firstName} {u.lastName}</h4>
                        <div className="flex items-center gap-2 text-xs text-gray-500 mt-0.5">
                          <span>{u.email}</span>
                          {(u.companyName) && (
                            <>
                              <span className="w-1 h-1 bg-gray-300 rounded-full" />
                              <span>{u.companyName}</span>
                            </>
                          )}
                        </div>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      {processingId === u.id ? (
                        <Loader2 className="w-5 h-5 animate-spin text-gray-400 mr-4" />
                      ) : (
                        <>
                          <button onClick={() => handleApprove(u.id)} className="px-4 py-2 text-xs font-semibold bg-gray-900 text-white rounded-lg hover:bg-gray-800 transition-colors">
                            {t("admin.approve")}
                          </button>
                          <button onClick={() => handleReject(u.id)} className="px-4 py-2 text-xs font-semibold text-gray-500 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors">
                            {t("admin.reject")}
                          </button>
                        </>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* USERS LIST */}
        {activeTab === "users" && (
          <div className="animate-in fade-in duration-300">
            <div className="relative mb-6 max-w-sm">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
              <input
                type="text"
                placeholder={t("admin.searchPlaceholder")}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="w-full pl-9 pr-4 py-2 bg-white border border-gray-200/80 rounded-xl text-sm outline-none focus:border-gray-400 transition-colors shadow-sm"
              />
            </div>
            
            <div className="bg-white rounded-2xl border border-gray-200/60 shadow-sm overflow-hidden">
              <table className="w-full text-left text-sm border-collapse">
                <thead>
                  <tr className="border-b border-gray-100">
                    <th className="px-6 py-4 font-semibold text-gray-500 text-xs">{t("admin.colUser")}</th>
                    <th className="px-6 py-4 font-semibold text-gray-500 text-xs hidden md:table-cell">{t("admin.colEmail")}</th>
                    <th className="px-6 py-4 font-semibold text-gray-500 text-xs">{t("admin.colRole")}</th>
                    <th className="px-6 py-4 font-semibold text-gray-500 text-xs hidden sm:table-cell">{t("admin.colLastLogin")}</th>
                    <th className="px-6 py-4 font-semibold text-gray-500 text-xs text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                  {filteredUsers.map(u => (
                    <tr 
                      key={u.id} 
                      onClick={() => setSelectedUserId(u.id)}
                      className="group hover:bg-gray-50/80 cursor-pointer transition-colors"
                    >
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-3">
                          <div className="w-8 h-8 rounded-full bg-gray-100 flex items-center justify-center text-gray-600 font-bold text-xs uppercase shrink-0 group-hover:bg-white transition-colors">
                            {u.firstName.charAt(0)}{u.lastName.charAt(0)}
                          </div>
                          <span className="font-medium text-gray-900">{u.firstName} {u.lastName}</span>
                        </div>
                      </td>
                      <td className="px-6 py-4 text-gray-500 hidden md:table-cell">{u.email}</td>
                      <td className="px-6 py-4">
                        <span className={`inline-block px-2 py-1 text-[10px] font-bold uppercase rounded-md tracking-wide ${
                          u.role === 'EXPERT' ? 'bg-orange-50 text-orange-700' : 'bg-gray-100 text-gray-600'
                        }`}>
                          {u.role}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-gray-400 text-xs hidden sm:table-cell">
                        {formatRelativeTime(u.lastLogin, t)}
                      </td>
                      <td className="px-6 py-4 text-right" onClick={(e) => e.stopPropagation()}>
                        <button
                          onClick={() => handleDeleteUser(u.id, `${u.firstName} ${u.lastName}`)}
                          className="p-1.5 text-gray-400 hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                          title={t("admin.deleteUser")}
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </td>
                    </tr>
                  ))}
                  {filteredUsers.length === 0 && (
                    <tr>
                      <td colSpan={4} className="px-6 py-12 text-center text-gray-400">{t("admin.noUsers")}</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>

      {/* --- DRAWER (User Details) --- */}
      {selectedUser && (
        <div className="fixed inset-0 z-50 flex justify-end">
          <div className="absolute inset-0 bg-gray-900/20 backdrop-blur-sm transition-opacity animate-in fade-in" onClick={() => setSelectedUserId(null)} />
          <div className="relative w-full max-w-sm bg-white h-full shadow-2xl flex flex-col border-l border-gray-200 animate-in slide-in-from-right duration-300">
            
            {/* Drawer Header */}
            <div className="px-6 py-6 border-b border-gray-100 flex items-start justify-between">
              <div className="flex gap-4">
                <div className="w-12 h-12 rounded-full bg-gray-100 flex items-center justify-center text-gray-600 font-bold text-lg uppercase shrink-0 mt-1">
                  {selectedUser.firstName.charAt(0)}{selectedUser.lastName.charAt(0)}
                </div>
                <div>
                  <h2 className="text-lg font-bold text-gray-900">{selectedUser.firstName} {selectedUser.lastName}</h2>
                  <p className="text-sm text-gray-500">{selectedUser.email}</p>
                </div>
              </div>
              <button onClick={() => setSelectedUserId(null)} className="p-2 text-gray-400 hover:text-gray-900 bg-gray-50 hover:bg-gray-100 rounded-full transition-colors">
                <X className="w-4 h-4" />
              </button>
            </div>
            
            {/* Drawer Body */}
            <div className="flex-1 overflow-y-auto px-6 py-6 space-y-6">
              
              {/* Informations */}
              <section>
                <h3 className="text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-4">Informations</h3>
                <dl className="space-y-4">
                  <div>
                    <dt className="text-xs text-gray-500 mb-1">Entreprise</dt>
                    <dd className="text-sm font-medium text-gray-900">{selectedUser.companyName || "—"}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-gray-500 mb-1">Fonction</dt>
                    <dd className="text-sm font-medium text-gray-900">{selectedUser.companyRole || "—"}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-gray-500 mb-1">Statut du compte</dt>
                    <dd className="text-sm font-medium text-gray-900 flex items-center gap-1.5">
                      <div className={`w-2 h-2 rounded-full ${selectedUser.enabled ? 'bg-green-500' : 'bg-gray-300'}`} />
                      {selectedUser.enabled ? t("admin.enabled") : t("admin.disabled")}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-gray-500 mb-1">Dernière connexion</dt>
                    <dd className="text-sm font-medium text-gray-900">
                      {selectedUser.lastLogin ? new Date(selectedUser.lastLogin).toLocaleString("fr-FR") : "Jamais"}
                    </dd>
                  </div>
                </dl>
              </section>

              <hr className="border-gray-100" />

              {/* Rôle & Accès */}
              <section>
                <h3 className="text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-4">Rôle & Accès</h3>
                <div className="bg-gray-50 rounded-xl p-4 border border-gray-100/60">
                  <label className="block text-xs font-medium text-gray-500 mb-2">Modifier le rôle</label>
                  <div className="relative">
                    <select
                      value={selectedUser.role}
                      onChange={(e) => handleRoleChange(selectedUser.id, e.target.value)}
                      disabled={processingId === selectedUser.id}
                      className="w-full bg-white border border-gray-200 text-sm font-semibold rounded-lg px-3 py-2.5 outline-none focus:border-gray-400 transition-colors appearance-none"
                    >
                      <option value="USER">Utilisateur (Standard)</option>
                      <option value="EXPERT">Expert</option>
                      <option value="ADMIN">Administrateur</option>
                    </select>
                    {processingId === selectedUser.id && (
                      <Loader2 className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 animate-spin text-gray-400" />
                    )}
                  </div>
                  <p className="text-xs text-gray-400 mt-3 leading-relaxed">
                    Le rôle expert permet de modifier les températures de référence, les valeurs Z, et de piloter précisément les barèmes.
                  </p>
                </div>
              </section>

              <hr className="border-gray-100" />

              {/* Danger Zone */}
              <section className="pt-2">
                <button
                  onClick={() => handleDeleteUser(selectedUser.id, `${selectedUser.firstName} ${selectedUser.lastName}`)}
                  disabled={processingId === selectedUser.id}
                  className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-red-50 hover:bg-red-100 border border-red-200 text-red-700 text-xs font-bold rounded-xl transition-colors disabled:opacity-50"
                >
                  {processingId === selectedUser.id ? (
                    <Loader2 className="w-4 h-4 animate-spin" />
                  ) : (
                    <>
                      <Trash2 className="w-4 h-4" />
                      {t("admin.deleteUser")}
                    </>
                  )}
                </button>
              </section>

            </div>
          </div>
        </div>
      )}

    </div>
  );
}
