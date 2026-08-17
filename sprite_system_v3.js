import * as THREE from 'three';

export class SpriteSystemV3 {
    constructor(scene) {
        this.scene = scene;
    }

    createSprite(texture, width = 1, height = 2) {
        const mat = new THREE.SpriteMaterial({ map: texture, transparent: true });
        const sprite = new THREE.Sprite(mat);
        sprite.scale.set(width, height, 1);
        sprite.userData.height = height;
        
        this.scene.add(sprite);
        return sprite;
    }

    // Set position taking sprite pivot height into account
    setPosition(sprite, x, groundY, z) {
        const pivotOffsetY = (sprite.userData.height || 1) * 0.5;
        sprite.position.set(x, groundY + pivotOffsetY, z);
    }
}