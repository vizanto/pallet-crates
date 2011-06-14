(ns pallet.crate.ssh-key
  "Crate functions for manipulating SSH-keys"
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.remote-file :as remote-file]
   [pallet.parameter :as parameter]
   [pallet.script.lib :as lib]
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]
   [pallet.utils :as utils]
   [clojure.string :as string]))

(defn user-ssh-dir [user]
  (str (stevedore/script (~lib/user-home ~user)) "/.ssh/"))

(action/def-bash-action authorize-key-action
  "Designed to be used by authorize-key. This is an action to allow
   passing of delayed arguments for the public-key-string, enabling the
   authorisation of a key found with record-key."
  [session user public-key-string auth-file]
  (stevedore/checked-script
   "authorize-key"
   (var auth_file ~auth-file)
   (if-not (fgrep (quoted ~(string/trim public-key-string)) @auth_file)
     (echo (quoted ~public-key-string) ">>" @auth_file))))

(defn authorize-key
  "Authorize a public key on the specified user."
  [session user public-key-string & {:keys [authorize-for-user] :as options}]
  (let [target-user (or authorize-for-user user)
        dir (user-ssh-dir target-user)
        auth-file (str dir "authorized_keys")]
    (->
     session
     (directory/directory dir :owner target-user :mode "755")
     (file/file auth-file :owner target-user :mode "644")
     (authorize-key-action user public-key-string auth-file))))

(defn authorize-key-for-localhost
  "Authorize a user's public key on the specified user, for ssh access to
  localhost.  The :authorize-for-user option can be used to specify the
  user to who's authorized_keys file is modified."
  [session user public-key-filename & {:keys [authorize-for-user] :as options}]
  (let [target-user (or authorize-for-user user)
        key-file (str (user-ssh-dir user) public-key-filename)
        auth-file (str (user-ssh-dir target-user) "authorized_keys")]
    (->
     session
     (directory/directory
      (user-ssh-dir target-user) :owner target-user :mode "755")
     (file/file auth-file :owner target-user :mode "644")
     (exec-script/exec-checked-script
      "authorize-key"
      (var key_file ~key-file)
      (var auth_file ~auth-file)
      (if-not (grep (quoted @(cat @key_file)) @auth_file)
        (do
          (echo -n (quoted "from=\\\"localhost\\\" ") ">>" @auth_file)
          (cat @key_file ">>" @auth_file)))))))

(defn install-key
  "Install a ssh private key."
  [session user key-name private-key-string public-key-string]
  (let [ssh-dir (user-ssh-dir user)]
    (->
     session
     (directory/directory ssh-dir :owner user :mode "755")
     (remote-file/remote-file
      (str ssh-dir key-name)
      :owner user :mode "600"
      :content private-key-string)
     (remote-file/remote-file
      (str ssh-dir key-name ".pub")
      :owner user :mode "644"
      :content public-key-string))))

(def ssh-default-filenames
     {"rsa1" "identity"
      "rsa" "id_rsa"
      "dsa" "id_dsa"})

(defn generate-key
  "Generate an ssh key pair for the given user, unless one already
   exists. Options are:
     :file path     -- output file name (within ~user/.ssh directory)
     :type key-type -- key type selection
     :no-dir true   -- do note ensure directory exists
     :passphrase    -- new passphrase for encrypring the private key
     :comment       -- comment for new key"
  [session user & {:keys [type file passphrase no-dir comment]
                   :or {type "rsa" passphrase ""}
                   :as  options}]
  (let [key-type type
        path (stevedore/script
              ~(str (user-ssh-dir user)
                    (or file (ssh-default-filenames key-type))))
        ssh-dir (.getParent (java.io.File. path))]
    (->
     session
     (thread-expr/when-not->
      (or (:no-dir options))
      (directory/directory ssh-dir :owner user :mode "755"))
     (exec-script/exec-checked-script
      "ssh-keygen"
      (var key_path ~path)
      (if-not (file-exists? @key_path)
        (ssh-keygen ~(stevedore/map-to-arg-string
                      {:f (stevedore/script @key_path)
                       :t key-type
                       :N passphrase
                       :C (or (:comment options "generated by pallet"))}))))
     (file/file path :owner user :mode "0600")
     (file/file (str path ".pub") :owner user :mode "0644"))))

(defn record-public-key
  "Record a public key"
  [session user & {:keys [filename type parameter-path]
                   :or {type "rsa"} :as options}]
  (let [filename (or filename (ssh-default-filenames type))
        path (str (user-ssh-dir user) filename ".pub")]
    (->
     session
     (remote-file/with-remote-file
       (action/as-clj-action
        (fn [session local-path]
          (let [pub-key (slurp local-path)]
            (if-not (string/blank? pub-key)
              (if parameter-path
                (parameter/update-for-service
                 session parameter-path
                 (fn [keys] (conj (or keys #{}) pub-key)))
                (parameter/assoc-for-target
                 session [:user (keyword user) (keyword filename)] pub-key))
              session)))
        [session local-path])
       path))))

#_
(pallet.core/defnode a {}
  :bootstrap (pallet.phase/phase-fn
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.phase/phase-fn
              (pallet.crate.ssh-key/generate-key "duncan")
              (pallet.crate.ssh-key/record-public-key "duncan")))
