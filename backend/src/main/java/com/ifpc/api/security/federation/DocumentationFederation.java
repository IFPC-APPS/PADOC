package com.ifpc.api.security.federation;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Le contrat d'intégration, servi par le service qui l'applique.
 *
 * <p><b>Pourquoi une page dans l'application et non un fichier du dépôt.</b> Le
 * lecteur visé est l'équipe d'une plateforme partenaire : elle n'a ni compte
 * IFPC, ni accès au dépôt, et découvre ce service par son URL. Un document qui
 * vit dans {@code docs/} ne lui parvient que si quelqu'un le lui envoie, et la
 * copie qu'elle recevra vieillira sans que personne ne le sache.</p>
 *
 * <p><b>Les URL affichées sont celles de l'instance qui répond</b>, lues dans la
 * configuration du serveur d'autorisation. Une documentation qui donne des
 * exemples à recopier doit donner les vraies adresses : un lecteur qui lit
 * « https://exemple » adapte à la main, et se trompe.</p>
 *
 * <p>Publique, comme le document de découverte qu'elle commente : elle ne révèle
 * que des noms d'endpoints et de claims, tous déjà lisibles sur
 * {@code /.well-known/openid-configuration}. Elle ne liste pas les plateformes
 * enregistrées — cela n'aiderait personne et renseignerait un attaquant.</p>
 */
@Controller
@RequiredArgsConstructor
public class DocumentationFederation {

    public static final String CHEMIN = "/federation/documentation";

    private final AuthorizationServerSettings reglages;

    @GetMapping(value = CHEMIN, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String afficher(HttpServletRequest requete) {
        String racine = racine(requete);
        return GABARIT
                .replace("{{racine}}", racine)
                .replace("{{decouverte}}", racine + "/.well-known/openid-configuration")
                .replace("{{autorisation}}", racine + reglages.getAuthorizationEndpoint())
                .replace("{{jeton}}", racine + reglages.getTokenEndpoint())
                .replace("{{jwks}}", racine + reglages.getJwkSetEndpoint())
                .replace("{{userinfo}}", racine + reglages.getOidcUserInfoEndpoint())
                .replace("{{revocation}}", racine + reglages.getTokenRevocationEndpoint())
                .replace("{{deconnexion}}", racine + reglages.getOidcLogoutEndpoint());
    }

    /**
     * L'origine à afficher : celle configurée si elle l'est, sinon celle de la
     * requête.
     *
     * <p>L'émetteur configuré fait foi — c'est la valeur que les plateformes
     * compareront au claim {@code iss}. Faute de configuration, on retombe sur
     * l'adresse par laquelle le lecteur est arrivé, qui est au moins joignable.</p>
     */
    private String racine(HttpServletRequest requete) {
        String emetteur = reglages.getIssuer();
        if (emetteurConfigure()) {
            return emetteur.endsWith("/") ? emetteur.substring(0, emetteur.length() - 1) : emetteur;
        }
        return ServletUriComponentsBuilder.fromRequestUri(requete)
                .replacePath(null).build().toUriString();
    }

    private boolean emetteurConfigure() {
        String emetteur = reglages.getIssuer();
        return emetteur != null && !emetteur.isBlank();
    }


    // Page autonome : aucune ressource externe. Elle doit s'afficher même
    // derrière un réseau qui filtre les CDN, et rester lisible dans les deux
    // thèmes du système.
    private static final String GABARIT = """
            <!doctype html>
            <html lang="fr"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Se connecter avec IFPC — guide d'intégration</title>
            <style>
              :root{
                --fond:#f6f7f5; --carte:#fff; --bord:#e2e5df; --texte:#1f2420;
                --doux:#5c645e; --accent:#2f6b3c; --accent-doux:#eef4ef;
                --code-fond:#f2f4f1; --alerte-fond:#fdf7ee; --alerte-bord:#efdcc0;
                --alerte-texte:#7a5320;
              }
              @media (prefers-color-scheme: dark){
                :root{
                  --fond:#141715; --carte:#1c211e; --bord:#2e352f; --texte:#e6eae6;
                  --doux:#9aa39c; --accent:#7fc08f; --accent-doux:#1f2a22;
                  --code-fond:#232a25; --alerte-fond:#2a2318; --alerte-bord:#4a3c22;
                  --alerte-texte:#e0c28c;
                }
              }
              *{box-sizing:border-box}
              body{margin:0;background:var(--fond);color:var(--texte);
                   font:15px/1.65 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
              .page{max-width:52rem;margin:0 auto;padding:2.5rem 1.25rem 5rem}
              header{border-bottom:1px solid var(--bord);padding-bottom:1.5rem;margin-bottom:2rem}
              h1{font-size:1.6rem;margin:0 0 .5rem;letter-spacing:-.01em}
              .chapeau{color:var(--doux);margin:0;max-width:40rem}
              h2{font-size:1.15rem;margin:2.75rem 0 .75rem;padding-top:.5rem}
              h3{font-size:.95rem;margin:1.75rem 0 .5rem}
              p,li{margin:.6rem 0}
              ul,ol{padding-left:1.35rem}
              a{color:var(--accent)}
              code{background:var(--code-fond);padding:.1rem .35rem;border-radius:4px;
                   font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.875em}
              pre{background:var(--code-fond);border:1px solid var(--bord);border-radius:8px;
                  padding:.9rem 1rem;overflow-x:auto;margin:.85rem 0}
              pre code{background:none;padding:0;font-size:.82rem;line-height:1.6}
              .tableau{overflow-x:auto;margin:.85rem 0}
              table{border-collapse:collapse;width:100%;font-size:.9rem;min-width:30rem}
              th,td{text-align:left;padding:.5rem .7rem;border-bottom:1px solid var(--bord);
                    vertical-align:top}
              th{font-weight:600;color:var(--doux);font-size:.8rem;text-transform:uppercase;
                 letter-spacing:.04em}
              .note{background:var(--alerte-fond);border:1px solid var(--alerte-bord);
                    color:var(--alerte-texte);border-radius:8px;padding:.85rem 1rem;margin:1.1rem 0}
              .note strong{color:inherit}
              .note :first-child{margin-top:0} .note :last-child{margin-bottom:0}
              .cle{background:var(--accent-doux);border:1px solid var(--bord);border-radius:8px;
                   padding:1rem 1.15rem;margin:1.25rem 0}
              .cle :first-child{margin-top:0} .cle :last-child{margin-bottom:0}
              .etapes{counter-reset:e;list-style:none;padding:0}
              .etapes>li{counter-increment:e;position:relative;padding-left:2.2rem;margin:1rem 0}
              .etapes>li::before{content:counter(e);position:absolute;left:0;top:.05rem;
                   width:1.5rem;height:1.5rem;border-radius:50%;background:var(--accent);
                   color:var(--fond);font-size:.8rem;font-weight:700;display:grid;
                   place-items:center}
              footer{margin-top:3.5rem;padding-top:1.25rem;border-top:1px solid var(--bord);
                     color:var(--doux);font-size:.85rem}
            </style></head>
            <body><div class="page">

            <header>
              <h1>Se connecter avec IFPC</h1>
              <p class="chapeau">Guide d'intégration pour les plateformes partenaires. IFPC
              fournit l'authentification : vos utilisateurs se connectent avec leur compte
              IFPC, vous n'avez pas d'identifiants à créer ni de mots de passe à garder.</p>
            </header>

            <div class="cle">
              <p><strong>Une seule URL à retenir.</strong> Tout le reste s'en déduit
              automatiquement — n'écrivez aucune autre adresse en dur, elle pourrait
              changer&nbsp;:</p>
              <pre><code>{{decouverte}}</code></pre>
              <p>C'est un document de découverte OpenID Connect standard. Toute
              bibliothèque OIDC sait le lire et se configurer seule à partir de lui.</p>
            </div>

            <h2>1. Ce qu'il vous faut avant de commencer</h2>
            <p>Deux choses, à demander à l'administrateur IFPC&nbsp;:</p>
            <ul>
              <li>un <code>client_id</code> pour votre plateforme&nbsp;;</li>
              <li>l'enregistrement de vos <strong>URI de redirection</strong>, en
              correspondance exacte — aucun caractère joker n'est accepté. Prévoyez-les
              pour chacun de vos environnements (développement, recette, production)&nbsp;:
              une URI non enregistrée est refusée.</li>
            </ul>
            <p>Si votre plateforme a un serveur capable de garder un secret, demandez
            aussi un <code>client_secret</code> — voir §3.</p>

            <div class="note">
              <p><strong>Tout compte IFPC validé peut se connecter à votre plateforme.</strong>
              Aucune démarche par utilisateur n'est nécessaire&nbsp;: venir d'IFPC suffit.
              Ce que la personne peut faire chez vous dépend des rôles transmis dans le
              jeton (§6), que vous gérez avec l'administrateur IFPC.</p>
              <p>Un compte dont l'inscription n'est pas encore validée voit une page
              « Compte en attente de validation » et <strong>aucun code d'autorisation
              n'est émis</strong>. Ce n'est pas une panne de votre intégration.</p>
            </div>

            <h2>2. Le parcours</h2>
            <p>Authorization Code + PKCE, sans variante&nbsp;:</p>
            <ol class="etapes">
              <li>L'utilisateur clique « Se connecter avec IFPC » chez vous.</li>
              <li>Vous le redirigez vers notre point d'autorisation avec un
                  <code>code_challenge</code>.</li>
              <li>Il s'authentifie chez IFPC — son mot de passe ne transite jamais par
                  vous.</li>
              <li>Il accorde son consentement (une seule fois&nbsp;: nous le
                  mémorisons).</li>
              <li>Nous le renvoyons sur votre <code>redirect_uri</code> avec un
                  <code>code</code> à usage unique, valable une minute.</li>
              <li>Vous échangez ce code contre les jetons, en fournissant le
                  <code>code_verifier</code>.</li>
              <li>Vous vérifiez la signature (§5), puis créez le compte local à la
                  première connexion.</li>
            </ol>

            <h3>Points d'entrée</h3>
            <div class="tableau"><table>
              <tr><th>Rôle</th><th>URL</th></tr>
              <tr><td>Découverte</td><td><code>{{decouverte}}</code></td></tr>
              <tr><td>Autorisation</td><td><code>{{autorisation}}</code></td></tr>
              <tr><td>Jetons</td><td><code>{{jeton}}</code></td></tr>
              <tr><td>Clés publiques (JWKS)</td><td><code>{{jwks}}</code></td></tr>
              <tr><td>Profil utilisateur</td><td><code>{{userinfo}}</code></td></tr>
              <tr><td>Révocation</td><td><code>{{revocation}}</code></td></tr>
              <tr><td>Déconnexion</td><td><code>{{deconnexion}}</code></td></tr>
            </table></div>

            <h2>3. Votre plateforme est-elle un client public ou confidentiel&nbsp;?</h2>
            <p>C'est votre architecture qui décide, pas nous. Les deux sont acceptés.</p>
            <div class="tableau"><table>
              <tr><th></th><th>Client confidentiel</th><th>Client public</th></tr>
              <tr><td>Quand</td><td>vous avez un serveur</td>
                  <td>application de navigateur servie en statique</td></tr>
              <tr><td><code>client_secret</code></td><td>oui, jamais dans le navigateur</td>
                  <td>aucun</td></tr>
              <tr><td>Échange du code</td><td>de serveur à serveur</td>
                  <td>depuis le navigateur</td></tr>
              <tr><td><code>refresh_token</code></td><td><strong>oui</strong>, à rotation</td>
                  <td><strong>non</strong> — voir ci-dessous</td></tr>
            </table></div>

            <div class="note">
              <p><strong>Un client public ne reçoit pas de <code>refresh_token</code>.</strong>
              Un jeton de rafraîchissement stocké dans un navigateur est un secret de longue
              durée exposé au moindre XSS&nbsp;: nous n'en émettons pas.</p>
              <p>Pour renouveler l'accès, repassez silencieusement par le point
              d'autorisation&nbsp;: la session IFPC de l'utilisateur est encore ouverte et son
              consentement déjà accordé, la redirection est donc invisible pour lui. Les
              bibliothèques OIDC courantes savent le faire. <strong>Si vous voulez un
              <code>refresh_token</code>, il vous faut un backend</strong> — donc être un
              client confidentiel.</p>
            </div>

            <p><strong>PKCE est exigé dans les deux cas</strong>, y compris d'un client
            confidentiel. Il lie le code d'autorisation à la session qui l'a demandé&nbsp;:
            un code capté dans un journal de serveur, un historique de navigation ou un
            en-tête <code>Referer</code> devient inutilisable. Les bibliothèques modernes le
            font par défaut.</p>

            <h2>4. Les deux jetons ne se remplacent pas</h2>
            <p>C'est la confusion la plus fréquente, et elle ouvre une faille&nbsp;:</p>
            <div class="tableau"><table>
              <tr><th></th><th><code>id_token</code></th><th><code>access_token</code></th></tr>
              <tr><td>Répond à</td><td>qui est cet utilisateur&nbsp;?</td>
                  <td>a-t-il le droit de faire ceci&nbsp;?</td></tr>
              <tr><td>Pour</td><td>vous, une fois, à la connexion</td>
                  <td>vos API, à chaque appel</td></tr>
              <tr><td>À envoyer à une API</td><td><strong>jamais</strong></td>
                  <td>oui, en <code>Authorization: Bearer</code></td></tr>
            </table></div>

            <h2>5. Ce que vous devez vérifier — sans exception</h2>
            <p>Une bibliothèque OIDC conforme fait tout ceci. Le risque est de la
            court-circuiter « juste pour lire l'e-mail »&nbsp;: <strong>un JWT décodé sans
            vérification est une chaîne fournie par le client, donc sans valeur.</strong></p>
            <ol>
              <li><strong>Signature</strong> valide contre une clé du JWKS, choisie par le
                  <code>kid</code> de l'en-tête.</li>
              <li><strong>Algorithme</strong> attendu (<code>RS256</code>), pris dans votre
                  configuration et <em>jamais</em> dans l'en-tête du jeton — accepter
                  l'algorithme qu'il annonce, <code>none</code> compris, est une faille
                  classique.</li>
              <li><strong><code>iss</code></strong> égal à <code>{{racine}}</code>.</li>
              <li><strong><code>aud</code></strong> contenant votre <code>client_id</code>,
                  et pas celui d'une autre plateforme.</li>
              <li><strong><code>exp</code></strong> non dépassé.</li>
              <li><strong><code>nonce</code></strong> identique à celui envoyé, pour
                  l'<code>id_token</code>.</li>
            </ol>
            <p>Mettez le JWKS en cache, mais <strong>rafraîchissez-le sur <code>kid</code>
            inconnu</strong>&nbsp;: nos clés tournent. Pendant la transition, l'ancienne et
            la nouvelle sont publiées ensemble — un cache jamais rafraîchi finira par
            rejeter des jetons valides.</p>

            <h2>6. Les informations que vous recevez</h2>
            <div class="tableau"><table>
              <tr><th>Claim</th><th>Portée</th><th>À savoir</th></tr>
              <tr><td><code>sub</code></td><td><code>openid</code></td>
                  <td><strong>La seule clé pour rattacher un compte local.</strong>
                  Opaque, stable à vie.</td></tr>
              <tr><td><code>email</code></td><td><code>email</code></td>
                  <td>peut changer&nbsp;: ne vous en servez pas comme clé</td></tr>
              <tr><td><code>email_verified</code></td><td><code>email</code></td>
                  <td><strong>vaut <code>false</code></strong> — voir ci-dessous</td></tr>
              <tr><td><code>given_name</code>, <code>family_name</code>,
                  <code>name</code></td><td><code>profile</code></td>
                  <td>affichage&nbsp;; facultatifs, donc parfois absents</td></tr>
              <tr><td><code>https://ifpc.eu/claims/roles</code></td><td>—</td>
                  <td>les rôles de l'utilisateur <strong>sur votre plateforme</strong></td></tr>
              <tr><td><code>https://ifpc.eu/claims/organisation</code></td>
                  <td><code>profile</code></td>
                  <td>déclaratif — voir l'avertissement</td></tr>
            </table></div>

            <div class="note">
              <p><strong><code>organisation</code> n'est pas vérifié.</strong> C'est un champ
              de profil libre, que l'utilisateur saisit et modifie lui-même, sans
              référentiel. Affichez-le si c'est utile, mais <strong>ne le prenez jamais pour
              une frontière d'autorisation</strong>&nbsp;: sinon n'importe qui atteint les
              données d'une entreprise en recopiant son nom dans son profil.</p>
              <p>Pour la même raison, <code>email_verified</code> vaut <code>false</code>&nbsp;:
              il n'existe pas encore de parcours de confirmation d'adresse chez IFPC.
              <strong>Ne rattachez donc jamais un compte local existant sur une simple
              égalité d'adresse</strong> — ce serait une prise de contrôle de compte.</p>
            </div>

            <h3>Les rôles sont les vôtres, pas les nôtres</h3>
            <p>Le claim <code>roles</code> ne contient que les rôles accordés à cet
            utilisateur <em>pour votre plateforme</em>, dans <strong>votre</strong>
            vocabulaire. Les rôles internes d'IFPC ne vous sont jamais transmis&nbsp;: ils
            n'ont de sens que chez nous. Dites à l'administrateur IFPC quels noms de rôles
            vous utilisez, il les saisira tels quels.</p>
            <p>Un utilisateur à qui aucun rôle n'a été accordé arrive chez vous avec un
            claim <code>roles</code> vide&nbsp;: il est connecté, sans droit particulier.
            Accès et rôles sont deux questions distinctes.</p>

            <h2>7. Durées de vie</h2>
            <div class="tableau"><table>
              <tr><th>Jeton</th><th>Durée</th><th>Révocable</th></tr>
              <tr><td>Code d'autorisation</td><td>1 minute, usage unique</td><td>—</td></tr>
              <tr><td><code>access_token</code></td><td>10 minutes</td>
                  <td>non — sa brièveté en tient lieu</td></tr>
              <tr><td><code>refresh_token</code></td><td>14 jours</td>
                  <td>oui, sur le point de révocation</td></tr>
            </table></div>
            <p>Le <code>refresh_token</code> est <strong>à rotation</strong>&nbsp;: chaque
            usage en émet un nouveau et invalide le précédent. Remplacez donc toujours celui
            que vous stockez. Un rejeu répond <code>invalid_grant</code> et signale un vol.</p>
            <p>Un rôle retiré par un administrateur IFPC prend effet au renouvellement
            suivant, soit dix minutes au plus.</p>

            <h2>8. Les refus que vous verrez</h2>
            <div class="tableau"><table>
              <tr><th>Ce que vous observez</th><th>Cause</th></tr>
              <tr><td>Page « Compte en attente de validation »</td>
                  <td>inscription IFPC pas encore approuvée par un administrateur.</td></tr>
              <tr><td>« Identifiants invalides, ou compte non encore validé »</td>
                  <td>message unique et volontairement ambigu, pour qu'on ne puisse pas
                  découvrir quelles adresses ont un compte.</td></tr>
              <tr><td><code>invalid_grant</code> à l'échange du code</td>
                  <td>code expiré (1 min), déjà utilisé, <code>code_verifier</code> qui ne
                  correspond pas, ou <code>redirect_uri</code> différente de celle de la
                  demande.</td></tr>
              <tr><td><code>invalid_grant</code> au renouvellement</td>
                  <td><code>refresh_token</code> déjà utilisé (rotation), révoqué ou
                  expiré.</td></tr>
              <tr><td>Redirection refusée</td>
                  <td><code>redirect_uri</code> non enregistrée&nbsp;: la correspondance est
                  exacte, au caractère près.</td></tr>
            </table></div>

            <h2>9. Ce que nous ne faisons pas</h2>
            <ul>
              <li><strong>Aucune synchronisation d'annuaire.</strong> Créez le compte local
              à la première connexion, à partir des claims.</li>
              <li><strong>Aucune notification de changement.</strong> Nous ne vous prévenons
              pas si un compte est désactivé&nbsp;: c'est l'expiration du jeton qui fait
              foi, d'où sa brièveté.</li>
              <li><strong>Aucun rattachement automatique</strong> à vos comptes existants
              (§6).</li>
            </ul>

            <footer>
              <p>Question d'intégration ou demande de <code>client_id</code>&nbsp;:
              service informatique IFPC.</p>
              <p>Émetteur de cette instance&nbsp;: <code>{{racine}}</code></p>
            </footer>

            </div></body></html>
            """;
}
