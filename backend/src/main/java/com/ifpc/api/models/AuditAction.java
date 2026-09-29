package com.ifpc.api.models;

/**
 * Opérations consignées au journal d'audit.
 *
 * <p>La liste est volontairement courte : on ne journalise pas l'usage normal
 * de l'application, seulement ce qui touche au registre de maîtrise sanitaire,
 * aux paramètres scientifiques ou aux droits d'accès. Un journal qui consigne
 * tout ne se lit pas.</p>
 */
public enum AuditAction {

    /** Une analyse a été retirée de l'historique consultable. */
    ANALYSE_SUPPRIMEE,

    /** Le rôle d'un utilisateur a changé. */
    ROLE_MODIFIE,

    /** Un compte a été approuvé, et son titulaire a obtenu l'accès. */
    COMPTE_APPROUVE,

    /** Un compte a été supprimé. Ses données restent rattachées à son adresse. */
    COMPTE_SUPPRIME,

    /** L'accès d'un utilisateur à une plateforme fédérée a été accordé ou modifié. */
    HABILITATION_ACCORDEE,

    /** L'accès d'un utilisateur à une plateforme fédérée a été retiré. */
    HABILITATION_RETIREE,

    /** La clé de signature des jetons fédérés a été renouvelée. */
    CLE_FEDERATION_TOURNEE,
}
